package bayou.http;

import _bayou._tmp._CharSeqSaver;
import _bayou._tmp._Util;
import bayou.async.Async;
import bayou.bytes.ByteSource;
import bayou.mime.HeaderMap;
import bayou.mime.Headers;
import bayou.tcp.TcpConnection;
import bayou.util.End;
import bayou.util.Result;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static bayou.http.ImplConn.Goto;

// send response
class ImplConnResp
{
    ImplConn hConn;
    TcpConnection nbConn;
    HttpResponseImpl response;
    boolean isLast;
    int httpMinorVersion;   // 0 or 1. -1 if request parse error

    long highMark;
    long minThroughput; // >=0
    Duration writeTimeout;
    // also, source(response entity body) may be impatient for slow sink. source can adopt its own throughput policy,
    // it can close itself unilaterally to free some resources. we'll notice that when accessing it.

    long bodyLength;  // -1 if unknown. 0 or positive if known
    ByteSource body;
    Async<ByteBuffer> bodyPendingRead;

    long writeT0;

    ImplConnResp(ImplConn hConn, HttpResponseImpl response, boolean isLast,
                 int httpMinorVersion, ByteSource body, long bodyLength)
    {
        this.hConn = hConn;
        nbConn = hConn.nbConn;
        this.response = response;
        this.isLast = isLast;
        this.httpMinorVersion = httpMinorVersion;

        highMark = hConn.conf.outboundBufferSize;
        minThroughput = hConn.conf.writeMinThroughput;
        writeTimeout = hConn.conf.writeTimeout;

        this.body = body;
        this.bodyLength = bodyLength;
    }

    Goto startWrite()
    {
        writeT0 = System.currentTimeMillis();

        long r0 = nbConn.getWriteQueueSize(); // should be 0
        queueHead(response.status, response.headers, response.headersSetCookie());
        headLength = nbConn.getWriteQueueSize() - r0;

        // head is not written yet; will do it together with body,
        // even if head is bigger than confResponseBufferSize (unlikely).
        // head will be pushed to client promptly, even if body read stalls.

        return pipeBody();
    }

    void printHead(_CharSeqSaver out, HttpStatus status, HeaderMap headers, List<String> headersSetCookie)
    {
        // response version same as request version. is that necessary? why not always respond HTTP/1.1?
        String HTTP1xSP = httpMinorVersion==0? "HTTP/1.0 " : "HTTP/1.1 ";
        out.append(HTTP1xSP).append(status.toString()).append("\r\n");  // status chars were checked

        for(Map.Entry<String,String> nv : headers.entrySet())
        {
            String name = nv.getKey();
            String value = nv.getValue();
            // name value have been sanity checked. we'll not generate syntactically incorrect header.
            out.append(name).append(": ").append(value).append("\r\n");
        }
        for(String setCookie : headersSetCookie)   // setCookie guaranteed to be valid
            out.append(Headers.Set_Cookie).append(": ").append(setCookie).append("\r\n");

        out.append("\r\n");
    }

    void queueHead(HttpStatus status, HeaderMap headers, List<String> headersSetCookie)
    {
        _CharSeqSaver chars = new _CharSeqSaver( 4 + 4*headers.size() + 4*headersSetCookie.size() );
        printHead(chars, status, headers, headersSetCookie);
        nbConn.queueWrite(ByteBuffer.wrap(chars.toLatin1Bytes()));
    }

    void dumpResp()
    {
        _CharSeqSaver chars = new _CharSeqSaver( 32 );
        chars.append(hConn.respId());
        printHead(chars, response.status, response.headers, response.headersSetCookie());
        hConn.dump.print(chars);
    }

    // we don't have a fast path for bodyLength==0; it should be rare anyway.
    Goto pipeBody()   // body.read() -> conn.queue -> conn.write
    {
        // [read]
        Async<ByteBuffer> readAsync = bodyPendingRead;
        if(readAsync!=null)  // prev read pending. check again if it's completed
            bodyPendingRead=null;
        else
            readAsync = body.read();
        // body.read() timeout: body can take as much time as it likes to, we can wait indefinitely.
        // client/server can engage in a very long polling.
        // this is dangerous though, if app has a bug that the read never completes.

        Result<ByteBuffer> readResult = readAsync.pollResult();
        if( readResult==null )
        {
            // [read stall]
            bodyPendingRead = readAsync;

            // during read stall, we'll be actively sending queued writes to client asap, no hoarding.

            // PROBLEM: it's quite possible that the STALL is not real - read will complete by the next
            // task or tasks in the local event queue. see BytePipeTest.testThroughput().
            // then write() here will be a wasteful expensive sys call.
            // possible solution: schedule a low priority task for write which yields to normal tasks.
            // maybe by masking a task as nice(n), which is postponed after n normal tasks are run.

            long remaining;
            try
            {   remaining = nbConn_write();   }
            catch (Exception e)
            {   return connErr(e);   }

            if(remaining==0) // nothing more to write
            {
                // only read stall. await body read complete, then pipeBody()
                // note: non trivial time has passed since we checked read completion status
                return body_awaitReadComplete();  // -> pipeBody -> [read complete]
                // we can be awaiting for arbitrarily long time, that's ok, since body is from app.
            }
            else // write stalls too
            {
                // source read stall + sink write stall.
                // we will only await sink writable here. suppose source read completes before that,
                //    read + conn.queueWrite() +repeat doesn't seem to have any benefit, when sink stalls.
                //    the OS send buffer is full; unlikely read and queue can improve throughput/latency.
                //    if the sink is faster, sink is always writable; if source is faster, the bottleneck
                //    is the sink, proactively read and queue source bytes won't change that.
                // after sink becomes writable, we'll do pipe again, retesting source read completion status,
                // source may still stall, but sink makes progress by draining some/all queued writes.
                return nbConn_uponWritable(Goto.respPipeBody);  // -> pipeBody -> [read]
            }
        }
        // [read complete]
        ByteBuffer bb;
        try
        {   bb = readResult.getOrThrow();   }
        catch (End end)
        {   bb = null; }
        catch (Exception e)
        {   return bodyErr(e);   }

        if(bb==null) // EOF
        {
            if(bodyLength > 0)
            {
                // premature EOF
                assert bodyTotal< bodyLength; // see later code
                return bodyErr(new IllegalStateException(
                    "response entity body is shorter than Content-Length. "+bodyTotal+"<"+bodyLength));
                // it's a serious programming error. log and investigate. abort conn.
            }
            else // 0 or -1
            {
                // legit EOF
                // bodyLength 0 : read() should return *STALL + EOF
                // bodyLength unknown: EOF to signal end of body. body may be empty too.
                closeBody();
                return toFlushAll();
            }
        }

        // we got some bytes from the body. ( fine if bb.length()==0 )
        bodyTotal += bb.remaining();
        long writeRemaining = nbConn.queueWrite(bb);
        // bb will be closed after it's written; if it didn't get written due to connErr or serious bodyErr,
        // it'll be closed very soon when nbConn.close()

        if(bodyLength >= 0)
        {
            if(bodyTotal== bodyLength) // all source data are read (if bodyLength==0, this must be false)
            {
                // next body.read() should return EOF. but we are not going to verify that.
                closeBody();
                return toFlushAll();
            }
            if(bodyTotal > bodyLength)  // if bodyLength==0, this must be true
            {
                return bodyErr(new IllegalStateException(
                    "response entity body is larger than Content-Length. "+bodyTotal+">"+bodyLength));
                // it's a serious programming error. log and investigate. abort conn.
                // we must not send extra bytes to client. it's framing error.
                // do not correct it by slicing. body is not trust worthy. just abort.
            }
            // bodyTotal<bodyLength, carry on
        }
        // else length unknown, no way to check

        if(writeRemaining> highMark)  // drain till under high mark
            return Goto.respDrainMark;

        // under high mark;
        return Goto.respPipeBody;  // loop pipeBody
    }

    long headLength;
    long writtenTotal;
    long bodyTotal;
    long readStallT0;
    long readStallTime;
    long nbConn_write() throws Exception  // return bytes remaining, not bytes written
    {
        long w = nbConn.write(); // throws
        writtenTotal += w;

        long remain = nbConn.getWriteQueueSize();
        if(remain>0) // check throughput when write stalls
        {
            long timeSpent = System.currentTimeMillis() - writeT0 - readStallTime;  // exclude our read stall time.
            // other than our read stall time, all overhead from us are included, and blamed on client.
            // that should be fine on normal loads. on a busy system tho, it's unclear how to get accurate
            // timeSpent that's fairly incurred by client.
            // because the default min download throughput is quite low, if it's reached for many clients
            // due to system overload, there is a big problem anyway.
            // server probably should limit connections, so it never reaches such overload state.
            // if server is willing to server many concurrent clients slowly, set a very low min download throughput.
            if(timeSpent>10_000)  // don't check in the beginning
            {
                long minGoal = minThroughput * timeSpent / 1000;
                if(writtenTotal < minGoal)
                    throw new IOException("Client download throughput too low");  // as if network error
            }
        }

        return remain;
    }

    Goto body_awaitReadComplete()
    {
        // note: non trivial time gap since we last checked it's completion status
        if(bodyPendingRead.isCompleted())
            return bodyReadCompletes();

        // to calculate total read stall time
        readStallT0 = System.currentTimeMillis();

        // no timeout, we'll wait indefinitely for read complete
        bodyPendingRead.onCompletion(result ->
            hConn.jump(bodyReadCompletes()));
        return Goto.NA;
    }
    Goto bodyReadCompletes()
    {
        readStallTime += System.currentTimeMillis()-readStallT0;

        return Goto.respPipeBody;
    }

    Goto nbConn_uponWritable(final Goto g)
    {
        nbConn.awaitWritable().timeout(writeTimeout)
            .onCompletion(result -> {
                Exception error = result.getException();
                if (error != null)
                    hConn.jump(connErr(error));
                else
                    hConn.jump(g);
            });
        return Goto.NA;
    }

    public Goto drainMark() // drain under high mark
    {
        long remaining;
        try
        {   remaining = nbConn_write();   }
        catch (Exception e)
        {   return connErr(e);   }

        if(remaining> highMark)
            return nbConn_uponWritable(Goto.respDrainMark);  // loop drainMark
        else
            return Goto.respPipeBody;
    }

    Goto toFlushAll()
    {
        // body content is all queued. flush all.

        // must append CLOSE_NOTIFY and FIN if this is the last response
        if(isLast)
        {
            nbConn.queueWrite(TcpConnection.SSL_CLOSE_NOTIFY);
            nbConn.queueWrite(TcpConnection.TCP_FIN);
        }

        return Goto.respFlushAll;
    }

    // flush everything. ending: either conn err, or success.
    Goto flushAll()
    {
        long remaining;
        try
        {   remaining = nbConn_write();   }
        catch (Exception e)
        {   return connErr(e);   }

        if(remaining>0)
            return nbConn_uponWritable(Goto.respFlushAll);  // loop flushAll
        else // all flushed. no connErr. maybe prev benign bodyErr
            return Goto.respEnd;
    }


    Exception bodyError;
    Exception connError;
    // can have both errors : benign body error, continue to flush, then conn error.

    Goto bodyErr(Exception error)
    {
        // no prev connError

        closeBody();

        bodyError = error;

        // output stream is corrupt
        isLast = true;

        // serious app logic error. we don't care much about resp/conn/client if that happens
        if(_Util.unchecked(error))
            return Goto.respEnd;

        // benign exception, e.g. IOException. probably not app logic error.
        // couldn't read more from source, but still worthwhile to send client whatever we got
        return toFlushAll();
        // note: EOF is queued! though body is incomplete.
        // for HTTP/1.0, this EOF marks end-of-message, which is misleading. however if we close without EOF,
        //   in plain TCP, FIN probably will be sent upon close anyway; in SSL, lack of close_notify will
        //   make client think that there's truncation attack, which is false.
        // for HTTP/1.1, using either Content-Length or chunked-encoding,
        //   client will detect premature EOF before body is complete, that is good.
        //   client will immediately close connection, which ends server's draining step.
    }
    Goto connErr(Exception error)
    {
        // possible prev benign bodyError

        closeBody(); // body may have been closed, due to EOF, or prev body error.

        connError = error;

        // output stream is corrupt
        isLast = true;

        return Goto.respEnd;
    }

    void closeBody()
    {
        if(body ==null) // was closed
            return;

        final ByteSource bodyL = body;
        body = null;

        if(bodyPendingRead==null)
            bodyL.close();
        else // can't close during read pending; only after read is completed
        {
            bodyPendingRead.cancel(new Exception("cancelled"));
            bodyPendingRead.onCompletion(result ->
                bodyL.close());
            bodyPendingRead = null;
        }
    }


}
