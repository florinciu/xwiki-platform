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
package org.xwiki.classloader.internal.protocol.attachmentjar;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;

import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.bridge.DocumentAccessBridge;

/**
 * URL Connection that takes its content from a document attachment.
 *   
 * @version $Id$
 * @since 2.0.1
 */
public class AttachmentURLConnection extends URLConnection
{
    private DocumentAccessBridge documentAccessBridge;
    
    private AttachmentReference attachmentReference;

    /**
     * @param url the URL to connect to
     * @since 2.2M1
     */
    public AttachmentURLConnection(URL url, AttachmentReference attachmentReference, DocumentAccessBridge documentAccessBridge)
    {
        super(url);
        this.attachmentReference = attachmentReference;
        this.documentAccessBridge = documentAccessBridge;
    }

    /**
     * {@inheritDoc}
     * @see JarURLConnection#connect()
     */
    public void connect()
    {
        // Don't do anything since we don't need to connect to get the data...
    }

    /**
     * {@inheritDoc}
     * @see JarURLConnection#getInputStream()
     */
    public InputStream getInputStream()
    {
        try {
            return this.documentAccessBridge.getAttachmentContent(this.attachmentReference);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Attachment content for [" + this.attachmentReference + "]", e);
        }
    }
}
