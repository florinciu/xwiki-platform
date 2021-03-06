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
package org.xwiki.annotation.internal.renderer;

import java.util.Map;

import org.xwiki.annotation.content.AlteredContent;
import org.xwiki.annotation.content.ContentAlterer;
import org.xwiki.rendering.listener.HeaderLevel;
import org.xwiki.rendering.listener.ListType;
import org.xwiki.rendering.listener.chaining.ListenerChain;
import org.xwiki.rendering.renderer.AbstractChainingPrintRenderer;
import org.xwiki.rendering.syntax.Syntax;

/**
 * Plain text renderer that normalizes spaces in the printed text.
 * 
 * @version $Id$
 * @since 2.3M1
 */
public class PlainTextNormalizingChainingRenderer extends AbstractChainingPrintRenderer
{
    /**
     * Normalizer for the content serialized by this listener, to clean all texts such as protected strings in various
     * events.
     */
    private ContentAlterer textCleaner;

    /**
     * Flag to signal that the renderer is currently rendering whitespaces (has rendered the first one) and should not
     * append more. Starting true because we don't want to print beginning spaces.
     */
    private boolean isInWhitespace = true;

    /**
     * Flag to signal that this renderer has currently printed something. Cache for checking that serializing the
     * printer would return non zero characters, since serializing the printer at each step can be a bit too much.
     */
    private boolean hasPrinted;

    /**
     * Builds an abstract plain text normalizing renderer with the passed text cleaner.
     * 
     * @param textCleaner the text cleaner used to normalize the texts produced by the events
     * @param listenerChain the listeners chain this listener is part of
     */
    public PlainTextNormalizingChainingRenderer(ContentAlterer textCleaner, ListenerChain listenerChain)
    {
        this.textCleaner = textCleaner;
        setListenerChain(listenerChain);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.QueueListener#onWord(java.lang.String)
     */
    @Override
    public void onWord(String word)
    {
        printText(word);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.QueueListener#onSpecialSymbol(char)
     */
    @Override
    public void onSpecialSymbol(char symbol)
    {
        printText("" + symbol);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.QueueListener#onVerbatim(java.lang.String, boolean, java.util.Map)
     */
    @Override
    public void onVerbatim(String protectedString, boolean isInline, Map<String, String> parameters)
    {
        if (!isInline || Character.isWhitespace(protectedString.charAt(0))) {
            // if there is a space right at the beginning of the verbatim string, or the verbatim string is block, we
            // need to print a space
            printSpace();
        }

        AlteredContent cleanedContent = textCleaner.alter(protectedString);
        printText(cleanedContent.getContent().toString());

        if (!isInline || Character.isWhitespace(protectedString.charAt(protectedString.length() - 1))) {
            // print a space after
            printSpace();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.QueueListener#onRawText(java.lang.String, org.xwiki.rendering.syntax.Syntax)
     */
    @Override
    public void onRawText(String text, Syntax syntax)
    {
        // Similar approach to verbatim FTM. In the future, syntax specific cleaner could be used for various syntaxes
        // (which would do the great job for HTML, for example)
        // normalize the protected string before adding it to the plain text version
        if (Character.isWhitespace(text.charAt(0))) {
            // if there is a space right at the beginning of the raw text, we need to print a space
            printSpace();
        }

        AlteredContent cleanedContent = textCleaner.alter(text);
        printText(cleanedContent.getContent().toString());

        if (Character.isWhitespace(text.charAt(text.length() - 1))) {
            // if there is a space right at the end of the text, we need to print a space
            printSpace();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#onSpace()
     */
    @Override
    public void onSpace()
    {
        printSpace();
    }

    /**
     * Print a space to the renderer's printer.
     */
    protected void printSpace()
    {
        // start printing whitespaces
        isInWhitespace = true;
    }

    /**
     * Prints a text to the renderer's printer.
     * 
     * @param text the text to print
     */
    protected void printText(String text)
    {
        // if it's in whitespace and there was something printed before, print the remaining space, and then handle the
        // current text
        if (isInWhitespace && hasPrinted) {
            getPrinter().print(" ");
        }
        getPrinter().print(text);
        hasPrinted = true;
        isInWhitespace = false;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#onEmptyLines(int)
     */
    @Override
    public void onEmptyLines(int count)
    {
        if (count > 0) {
            printSpace();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#onNewLine()
     */
    @Override
    public void onNewLine()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#onHorizontalLine(java.util.Map)
     */
    @Override
    public void onHorizontalLine(Map<String, String> parameters)
    {
        printSpace();
    }

    // all next events are block, so spaces need to be printed around

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginDefinitionDescription()
     */
    @Override
    public void beginDefinitionDescription()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endDefinitionDescription()
     */
    @Override
    public void endDefinitionDescription()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginDefinitionList(java.util.Map)
     */
    @Override
    public void beginDefinitionList(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endDefinitionList(java.util.Map)
     */
    @Override
    public void endDefinitionList(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginDefinitionTerm()
     */
    @Override
    public void beginDefinitionTerm()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endDefinitionTerm()
     */
    @Override
    public void endDefinitionTerm()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginGroup(java.util.Map)
     */
    @Override
    public void beginGroup(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endGroup(java.util.Map)
     */
    @Override
    public void endGroup(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginHeader(org.xwiki.rendering.listener.HeaderLevel,
     *      java.lang.String, java.util.Map)
     */
    @Override
    public void beginHeader(HeaderLevel level, String id, Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endHeader(org.xwiki.rendering.listener.HeaderLevel,
     *      java.lang.String, java.util.Map)
     */
    @Override
    public void endHeader(HeaderLevel level, String id, Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginList(org.xwiki.rendering.listener.ListType,
     *      java.util.Map)
     */
    @Override
    public void beginList(ListType listType, Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endList(org.xwiki.rendering.listener.ListType,
     *      java.util.Map)
     */
    @Override
    public void endList(ListType listType, Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginListItem()
     */
    @Override
    public void beginListItem()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endListItem()
     */
    @Override
    public void endListItem()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginParagraph(java.util.Map)
     */
    @Override
    public void beginParagraph(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endParagraph(java.util.Map)
     */
    @Override
    public void endParagraph(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginQuotation(java.util.Map)
     */
    @Override
    public void beginQuotation(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endQuotation(java.util.Map)
     */
    @Override
    public void endQuotation(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginQuotationLine()
     */
    @Override
    public void beginQuotationLine()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endQuotationLine()
     */
    @Override
    public void endQuotationLine()
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginTable(java.util.Map)
     */
    @Override
    public void beginTable(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endTable(java.util.Map)
     */
    @Override
    public void endTable(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginTableRow(java.util.Map)
     */
    @Override
    public void beginTableRow(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endTableRow(java.util.Map)
     */
    @Override
    public void endTableRow(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginTableHeadCell(java.util.Map)
     */
    @Override
    public void beginTableHeadCell(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endTableHeadCell(java.util.Map)
     */
    @Override
    public void endTableHeadCell(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#beginTableCell(java.util.Map)
     */
    @Override
    public void beginTableCell(Map<String, String> parameters)
    {
        printSpace();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.chaining.AbstractChainingListener#endTableCell(java.util.Map)
     */
    @Override
    public void endTableCell(Map<String, String> parameters)
    {
        printSpace();
    }
}
