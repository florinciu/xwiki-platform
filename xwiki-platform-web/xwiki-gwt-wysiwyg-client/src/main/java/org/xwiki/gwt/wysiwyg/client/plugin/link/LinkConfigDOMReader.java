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
package org.xwiki.gwt.wysiwyg.client.plugin.link;

import org.xwiki.gwt.dom.client.DocumentFragment;
import org.xwiki.gwt.dom.client.Element;
import org.xwiki.gwt.user.client.EscapeUtils;
import org.xwiki.gwt.user.client.ui.rta.cmd.internal.AbstractInsertElementExecutable.ConfigDOMReader;
import org.xwiki.gwt.wysiwyg.client.plugin.image.ImageConfigDOMReader;
import org.xwiki.gwt.wysiwyg.client.plugin.link.LinkConfig.LinkType;

import com.google.gwt.dom.client.AnchorElement;
import com.google.gwt.dom.client.ImageElement;

/**
 * Creates an {@link LinkConfig} object from an {@link AnchorElement}.
 * 
 * @version $Id$
 */
public class LinkConfigDOMReader implements ConfigDOMReader<LinkConfig, AnchorElement>
{
    /**
     * The object used to extract the image file name when the anchor wraps an image.
     */
    private final ImageConfigDOMReader imageConfigHTMLParser = new ImageConfigDOMReader();

    /**
     * {@inheritDoc}
     * 
     * @see ConfigDOMReader#read(com.google.gwt.dom.client.Element)
     */
    public LinkConfig read(AnchorElement anchor)
    {
        LinkConfig linkConfig = new LinkConfig();

        // Get link meta data.
        DocumentFragment linkMetadata = Element.as(anchor).getMetaData();
        if (linkMetadata != null) {
            // Process the meta data.
            String startComment = linkMetadata.getChildNodes().getItem(0).getNodeValue();
            Element wrappingSpan = (Element) linkMetadata.getChildNodes().getItem(1);
            linkConfig.setReference(EscapeUtils.unescapeBackslash(startComment.substring("startwikilink:".length())));
            linkConfig.setType(readLinkType(wrappingSpan, linkConfig.getReference()));
        } else {
            // It's an external link.
            linkConfig.setType(LinkType.EXTERNAL);
        }

        // NOTE: We use getAttribute and not getHref to access the URL because we want the exact value of the attribute
        // and not the resolved (absolute) URL.
        linkConfig.setUrl(anchor.getAttribute("href"));
        linkConfig.setLabel(anchor.getInnerHTML());
        if (anchor.getChildNodes().getLength() == 1 && "img".equalsIgnoreCase(anchor.getFirstChild().getNodeName())) {
            // The anchor wraps an image.
            ImageElement image = (ImageElement) anchor.getFirstChild();
            linkConfig.setLabelText(imageConfigHTMLParser.read(image).getReference());
            linkConfig.setReadOnlyLabel(true);
        } else {
            linkConfig.setLabelText(anchor.getInnerText());
        }
        linkConfig.setTooltip(anchor.getTitle());
        linkConfig.setOpenInNewWindow("__blank".equals(anchor.getRel()));

        return linkConfig;
    }

    /**
     * Parses a link type from its wrapping span and from its reference.
     * 
     * @param wrappingSpan the link's wrapping span
     * @param reference the link reference
     * @return the link type, as parsed from it's wrapping span and from its reference
     */
    private LinkType readLinkType(Element wrappingSpan, String reference)
    {
        String wrappingSpanClass = wrappingSpan.getClassName();
        if ("wikilink".equals(wrappingSpanClass)) {
            return LinkType.WIKIPAGE;
        }
        if ("wikicreatelink".equals(wrappingSpanClass)) {
            return LinkType.NEW_WIKIPAGE;
        }
        if ("wikiexternallink".equals(wrappingSpanClass)) {
            if (reference.startsWith("mailto")) {
                return LinkType.EMAIL;
            } else if (reference.startsWith("attach")) {
                return LinkType.ATTACHMENT;
            }
        }
        return LinkType.EXTERNAL;
    }
}