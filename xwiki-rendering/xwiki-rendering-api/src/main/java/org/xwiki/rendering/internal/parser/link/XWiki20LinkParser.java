/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.rendering.internal.parser.link;

import org.apache.commons.lang.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Requirement;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.rendering.listener.DocumentLink;
import org.xwiki.rendering.listener.InterWikiLink;
import org.xwiki.rendering.listener.Link;
import org.xwiki.rendering.listener.LinkType;
import org.xwiki.rendering.parser.LinkParser;
import org.xwiki.rendering.parser.LinkTypeParser;
import org.xwiki.rendering.wiki.WikiModel;

import java.util.Arrays;
import java.util.List;

/**
 * Parses the content of XWiki 2.0 links.
 *
 * The supported generic format is as follows: <code>(link)(@interWikiAlias)?</code>, where:
 * <ul>
 * <li><code>link</code>: The full link reference using the following syntax:
 * <code>(reference)(#anchor)?(?queryString)?</code>, where:
 * <ul>
 * <li><code>reference</code>: The link reference. This can be either a URI in the form <code>protocol:path</code>
 * (example: "http://xwiki.org", "mailto:john@smith.com) or a wiki page name (example: "wiki:Space.WebHome").
 * Note that in the case of a wiki page name the character "\" is used as the escape character (for example if you
 * wish to have "#" or "?" in your page name you'll need to write "\#" and "\?").</li>
 * <li><code>anchor</code>: An optional anchor name pointing to an anchor defined in the referenced link. Note that in
 * XWiki anchors are automatically created for titles. Example: "TableOfContentAnchor".</li>
 * <li><code>queryString</code>: An optional query string for specifying parameters that will be used in the rendered
 * URL. Example: "mydata1=5&mydata2=Hello".</li>
 * </ul>
 * The <code>link</code> element is mandatory.</li>
 * <li><code>interWikiAlias</code>: An optional <a href="http://en.wikipedia.org/wiki/InterWiki">Inter Wiki</a> alias as
 * defined in the InterWiki Map. Example: "wikipedia"</li>
 * </ul>
 * Examples of valid wiki links:
 * <ul>
 * <li>Hello World</li>
 * <li>http://myserver.com/HelloWorld</li>
 * <li>HelloWorld#Anchor</li>
 * <li>Hello World@Wikipedia</li>
 * <li>mywiki:HelloWorld</li>
 * <li>Hello World?param1=1&param2=2</li>
 * </ul>
 *
 * Note that allowed URIs are URLs of the form {@code http://}, {@code mailto:},
 * {@code image:} and {@code attach:}.
 *
 * @version $Id$
 * @since 2.5M2
 */
@Component("xwiki/2.0")
public class XWiki20LinkParser implements LinkParser
{
    /**
     * Interwiki link separator.
     */
    public static final String SEPARATOR_INTERWIKI = "@";

    /**
     * Escapes to remove from the document reference part when parsing the raw reference (i.e. excluding query string,
     * anchor and interwiki parts).
     *
     * Note that we don't remove the escaped escape char since this is how an escape char is represented in an Entity
     * Reference.
     */
    private static final String[] ESCAPES_REFERENCE = new String[]{
        ESCAPE_CHAR + SEPARATOR_QUERYSTRING,
        ESCAPE_CHAR + SEPARATOR_INTERWIKI,
        ESCAPE_CHAR + SEPARATOR_ANCHOR };

    /**
     * Escapes to remove from the query string, anchor and interwiki parts when parsing the raw reference.
     */
    private static final String[] ESCAPES_EXTRA = new String[]{
        ESCAPE_CHAR + SEPARATOR_QUERYSTRING,
        ESCAPE_CHAR + SEPARATOR_INTERWIKI,
        ESCAPE_CHAR + SEPARATOR_ANCHOR,
        "" + ESCAPE_CHAR + ESCAPE_CHAR };

    /**
     * Escapes to remove the interwiki content.
     */
    private static final String[] ESCAPE_INTERWIKI = new String[]{
        "" + ESCAPE_CHAR + ESCAPE_CHAR,
        "" + ESCAPE_CHAR };

    /**
     * Replacement chars for the escapes to be removed from the reference part.
     */
    private static final String[] ESCAPE_REPLACEMENTS_REFERENCE = new String[]{
        SEPARATOR_QUERYSTRING,
        SEPARATOR_INTERWIKI,
        SEPARATOR_ANCHOR };

    /**
     * Replacement chars for the escapes to be removed from the query string, anchor and interwiki parts.
     */
    private static final String[] ESCAPE_REPLACEMENTS_EXTRA = new String[]{
        SEPARATOR_QUERYSTRING,
        SEPARATOR_INTERWIKI,
        SEPARATOR_ANCHOR,
        "" + ESCAPE_CHAR };

    /**
     * Replacements chars for the escapes to be removed from the interwiki content.
     */
    private static final String[] ESCAPE_REPLACEMENTS_INTERWIKI = new String[] {
        "" + ESCAPE_CHAR, "" };

    /**
     * The list of recognized URL prefixes.
     */
    private static final List<String> URI_PREFIXES = Arrays.asList("mailto", "image", "attach");

    /**
     * Parser to parse link references pointing to URLs.
     */
    @Requirement("url")
    private LinkTypeParser urlLinkTypeParser;

    /**
     * Used to verify if we're in wiki mode or not by looking up an implementation of {@link
     * org.xwiki.rendering.wiki.WikiModel}.
     */
    @Requirement
    private ComponentManager componentManager;

    /**
     * @return the list of URI prefixes the link parser recognizes
     */
    protected List<String> getAllowedURIPrefixes()
    {
        return URI_PREFIXES;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.xwiki.rendering.parser.LinkParser#parse(java.lang.String)
     */
    public Link parse(String rawLink)
    {
        // Step 1: If we're not in wiki mode then all links are URL links, except for link to images (since an image
        // link can point to an image defined as a URL.
        if (!isInWikiMode() && !rawLink.startsWith("image:")) {
            Link link = new Link();
            link.setType(LinkType.URL);
            link.setTyped(false);
            link.setReference(rawLink);
            return link;
        }

        // Step 2: Check if it's a known URI by looking for one of the known URI schemes. If not, check if it's a URL.
        Link link = parseURILinks(rawLink);
        if (link != null) {
            return link;
        }

        // Step 3: Look for an InterWiki link
        StringBuffer content = new StringBuffer(rawLink);
        link = parseInterWikiLinks(content);
        if (link != null) {
            return link;
        }

        // Step 4: Consider that we have a reference to a document.
        return parseDocumentLink(content);
    }

    /**
     * Construct a Document Link reference out of the passed content.
     *
     * @param content the string containing the Document link reference
     * @return the parsed Link Object corresponding to the Document link reference
     */
    private Link parseDocumentLink(StringBuffer content)
    {
        DocumentLink documentLink = new DocumentLink();

        String text = parseElementAfterString(content, LinkParser.SEPARATOR_QUERYSTRING);
        if (text != null) {
            documentLink.setQueryString(removeEscapesFromExtraParts(text));
        }

        text = parseElementAfterString(content, LinkParser.SEPARATOR_ANCHOR);
        if (text != null) {
            documentLink.setAnchor(removeEscapesFromExtraParts(text));
        }

        documentLink.setReference(removeEscapesFromReferencePart(content.toString()));
        documentLink.setTyped(false);
        
        return documentLink;
    }

    /**
     * Check if the passed link references is an URI link reference.
     *
     * @param rawLink the original reference to parse
     * @return the parsed Link object or null if the passed reference is not an URI link reference or if no URI type
     *         parser was found for the passed URI scheme
     */
    private Link parseURILinks(String rawLink)
    {
        Link result = null;
        int uriSchemeDelimiterPos = rawLink.indexOf(":");
        if (uriSchemeDelimiterPos > -1) {
            String scheme = rawLink.substring(0, uriSchemeDelimiterPos);
            String reference = rawLink.substring(uriSchemeDelimiterPos + 1);
            if (getAllowedURIPrefixes().contains(scheme)) {
                try {
                    Link link = this.componentManager.lookup(LinkTypeParser.class, scheme).parse(reference);
                    if (link != null) {
                        result = link;
                    }
                } catch (ComponentLookupException e) {
                    // Failed to lookup component, this shouldn't happen but ignore it.
                }
            } else {
                // Check if it's a URL
                Link link = this.urlLinkTypeParser.parse(rawLink);
                if (link != null) {
                    link.setTyped(false);
                    result = link;
                }
            }
        }
        return result;
    }

    /**
     * Check if the passed link references is an interwiki link reference.
     *
     * @param content the original content to parse
     * @return the parsed Link object or null if the passed reference is not an interwiki link reference
     */
    private Link parseInterWikiLinks(StringBuffer content)
    {
        Link result = null;
        String interWikiAlias = parseElementAfterString(content, SEPARATOR_INTERWIKI);
        if (interWikiAlias != null) {
            InterWikiLink link = new InterWikiLink();
            link.setInterWikiAlias(removeEscapes(interWikiAlias));
            link.setReference(removeEscapes(content.toString()));
            result = link;
        }
        return result;
    }

    /**
     * Find out the element located to the right of the passed separator.
     *
     * @param content the string to parse. This parameter will be modified by the method to remove the parsed content.
     * @param separator the separator string to locate the element
     * @return the parsed element or null if the separator string wasn't found
     */
    protected String parseElementAfterString(StringBuffer content, String separator)
    {
        String element = null;

        // Find the first non escaped separator (starting from the end of the content buffer).
        int index = content.lastIndexOf(separator);
        while (index != -1) {
            // Check if the element is found and it's not escaped.
            if (!shouldEscape(content, index)) {
                element = content.substring(index + separator.length()).trim();
                content.delete(index, content.length());
                break;
            }

            if (index > 0) {
                index = content.lastIndexOf(separator, index - 1);
            } else {
                break;
            }
        }

        return element;
    }

    /**
     * @return true if we're in wiki mode (ie there's no implementing class for {@link
     *         org.xwiki.rendering.wiki.WikiModel})
     */
    private boolean isInWikiMode()
    {
        boolean result = true;
        try {
            this.componentManager.lookup(WikiModel.class);
        } catch (ComponentLookupException e) {
            result = false;
        }
        return result;
    }

    /**
     * Count the number of escape chars before a given character and if that number is odd then that character should be
     * escaped.
     *
     * @param content the content in which to check for escapes
     * @param charPosition the position of the char for which to decide if it should be escaped or not
     * @return true if the character should be escaped
     */
    private boolean shouldEscape(StringBuffer content, int charPosition)
    {
        int counter = 0;
        int pos = charPosition - 1;
        while (pos > -1 && content.charAt(pos) == ESCAPE_CHAR) {
            counter++;
            pos--;
        }
        return (counter % 2 != 0);
    }

    /**
     * @param text the reference from which to remove unneeded escapes
     * @return the cleaned text
     */
    private String removeEscapesFromReferencePart(String text)
    {
        return StringUtils.replaceEach(text, ESCAPES_REFERENCE, ESCAPE_REPLACEMENTS_REFERENCE);
    }

    /**
     * @param text the reference from which to remove unneeded escapes
     * @return the cleaned text
     */
    private String removeEscapesFromExtraParts(String text)
    {
        return StringUtils.replaceEach(text, ESCAPES_EXTRA, ESCAPE_REPLACEMENTS_EXTRA);
    }

    /**
     * @param text the reference from which to remove unneeded escapes
     * @return the cleaned text
     */
    private String removeEscapes(String text)
    {
        return StringUtils.replaceEach(text, ESCAPE_INTERWIKI, ESCAPE_REPLACEMENTS_INTERWIKI);
    }
}