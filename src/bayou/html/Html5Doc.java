package bayou.html;

import bayou.mime.ContentType;
import bayou.text.TextDoc;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;


// basically a copy&paste of Html4Doc.java

/**
 * Html5 document.
 * <p>
 *     Usually Html5Doc is subclassed for each type of documents;
 *     the constructor builds the document using builder methods in {@link Html5}.
 *     For example
 * </p>
 * <pre>
    public class HelloHtml extends Html5Doc
    {
        public HelloHtml(String name)
        {
            _head(
                _link().type("text/css").href("/site.css")
            );

            _body(()-&gt;
            {
                _p("hello, ", name);
            });
        }
    }
 * </pre>
 * <p>
 *     See also {@link Html4Doc}.
 * </p>
 */
 
public class Html5Doc implements Html5, HtmlDoc
{
    /**
     * Create an html5 document.
     * <p>
     *     This constructor builds a basic html tree of
     * </p>
     * <pre>
     *     &lt;html&gt;
     *         &lt;head&gt;&lt;/head&gt;
     *         &lt;body&gt;&lt;/body&gt;
     *     &lt;/html&gt;
     * </pre>
     * <p>
     *     Subclass constructor should fill in more elements to &lt;head&gt; and &lt;body&gt;.
     * </p>
     */
    public Html5Doc()
    {
        html=new HTML();
        html.addChild(head = new HEAD());
        html.addChild(body = new BODY());
    }


    // the rest of code is copied from Html4Doc verbatim


    /** The &lt;html&gt; element. */
    protected final HTML html;
    /** The &lt;head&gt; element. */
    protected final HEAD head;
    /** The &lt;body&gt; element. */
    protected final BODY body;

    /**
     * Return the &lt;html&gt; element.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;html&gt; element,
     *     it returns the existing &lt;html&gt; element created in the constructor.
     *     The &lt;html&gt; already contains two children &lt;head&gt; and &lt;body&gt;,
     *     <b>do not add more children to it</b>.</i>
     * </p>
     */
    // user may need this method to add attributes to <html>
    @Override
    public HTML _html()
    {
        return html;
    }
    /**
     * Add children to the &lt;html&gt; element.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;html&gt; element,
     *     it returns the existing &lt;html&gt; element created in the constructor.
     *     The &lt;html&gt; already contains two children &lt;head&gt; and &lt;body&gt;,
     *     <b>do not add more children to it</b>.</i>
     * </p>
     * @deprecated Do not add more children to &lt;html&gt;
     */
    @Override
    public HTML _html(Object... children)
    {
        return html.add(children);
    }

    /**
     * Run `code` with the &lt;html&gt; element as the context parent.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;html&gt; element,
     *     it returns the existing &lt;html&gt; element created in the constructor.
     *     The &lt;html&gt; already contains two children &lt;head&gt; and &lt;body&gt;,
     *     <b>do not add more children to it</b>.</i>
     * </p>
     * @deprecated Do not add more children to &lt;html&gt;
     */
    @Override
    public HTML _html(Runnable code)
    {
        return html.add(code);
    }

    /**
     * Return the &lt;head&gt; element.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;head&gt; element,
     *     it returns the existing &lt;head&gt; element created in the constructor.</i>
     * </p>
     */
    @Override
    public HEAD _head()
    {
        return head;
    }
    /**
     * Add children to the &lt;head&gt; element.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;head&gt; element,
     *     it returns the existing &lt;head&gt; element created in the constructor.</i>
     * </p>
     */
    @Override
    public HEAD _head(Object... children)
    {
        return head.add(children);
    }
    /**
     * Run `code` with the &lt;head&gt; element as the context parent.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;head&gt; element,
     *     it returns the existing &lt;head&gt; element created in the constructor.</i>
     * </p>
     */
    @Override
    public HEAD _head(Runnable code)
    {
        return head.add(code);
    }

    /**
     * Return the &lt;body&gt; element.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;body&gt; element,
     *     it returns the existing &lt;body&gt; element created in the constructor.</i>
     * </p>
     */
    @Override
    public BODY _body()
    {
        return body;
    }

    /**
     * Add children to the &lt;body&gt; element.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;body&gt; element,
     *     it returns the existing &lt;body&gt; element created in the constructor.</i>
     * </p>
     */
    @Override
    public BODY _body(Object... children)
    {
        return body.add(children);
    }

    /**
     * Run `code` with the &lt;body&gt; element as the context parent.
     * <p>
     *     <i>This method overrides the super method with a different meaning.
     *     Instead of creating a new &lt;body&gt; element,
     *     it returns the existing &lt;body&gt; element created in the constructor.</i>
     * </p>
     */
    @Override
    public BODY _body(Runnable code)
    {
        return body.add(code);
    }

    /**
     * Print the content body to `out`.
     * <p>
     *     {@link #DOCTYPE} will be printed first,
     *     followed by the html tree.
     * </p>
     */
    @Override
    public void getContentBody(Consumer<CharSequence> out)
    {
        out.accept(DOCTYPE);
        out.accept(HtmlPiece.indent(0));
        html.render(0, out);
    }
}
