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
package org.xwiki.officeimporter.internal.cleaner;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.w3c.dom.Document;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Requirement;
import org.xwiki.component.logging.AbstractLogEnabled;
import org.xwiki.xml.html.HTMLCleaner;
import org.xwiki.xml.html.HTMLCleanerConfiguration;
import org.xwiki.xml.html.filter.HTMLFilter;

/**
 * {@link HTMLCleaner} for cleaning HTML coming from an wysiwyg office importer plugin.
 * 
 * @version $Id$
 * @since 1.8M1
 */
@Component("wysiwyg")
public class WysiwygHTMLCleaner extends AbstractLogEnabled implements HTMLCleaner
{
    /**
     * Default html cleaner component used internally. 
     */
    @Requirement
    private HTMLCleaner defaultHtmlCleaner;
    
    /**
     * {@link HTMLFilter} for stripping various tags.
     */
    @Requirement("officeimporter/stripper")
    private HTMLFilter stripperFilter;
    
    /**
     * {@link HTMLFilter} filtering styles.
     */
    @Requirement("officeimporter/style")
    private HTMLFilter styleFilter;
    
    /**
     * {@link HTMLFilter} for stripping redundant tags.
     */
    @Requirement("officeimporter/redundancy")
    private HTMLFilter redundancyFilter;
    
    /**
     * {@link HTMLFilter} for cleaning empty paragraphs.
     */
    @Requirement("officeimporter/paragraph")
    private HTMLFilter paragraphFilter;
    
    /**
     * {@link HTMLFilter} for filtering image tags.
     */
    @Requirement("officeimporter/image")
    private HTMLFilter imageFilter;

    /**
     * {@link HTMLFilter} for filtering lists.
     */
    @Requirement("officeimporter/list")
    private HTMLFilter listFilter;
    
    /**
     * {@link HTMLFilter} for filtering tables.
     */
    @Requirement("officeimporter/table")
    private HTMLFilter tableFilter;

    /**
     * {@inheritDoc}
     * 
     * @see HTMLCleaner#clean(Reader)
     */
    public Document clean(Reader originalHtmlContent)
    {
        return clean(originalHtmlContent, getDefaultConfiguration());
    }

    /**
     * {@inheritDoc}
     * 
     * @see HTMLCleaner#clean(Reader, HTMLCleanerConfiguration)
     */
    public Document clean(Reader originalHtmlContent, HTMLCleanerConfiguration configuration)
    {
        return this.defaultHtmlCleaner.clean(originalHtmlContent, configuration);
    }
    
    /**
     * {@inheritDoc}
     * 
     * @see HTMLCleaner#getDefaultConfiguration()
     */
    public HTMLCleanerConfiguration getDefaultConfiguration()
    {
        HTMLCleanerConfiguration configuration = this.defaultHtmlCleaner.getDefaultConfiguration();

        // Add OO cleaning filters after the default filters
        List<HTMLFilter> filters = new ArrayList<HTMLFilter>(configuration.getFilters());
        filters.addAll(Arrays.asList(
            this.stripperFilter, 
            this.styleFilter, 
            this.redundancyFilter,
            this.paragraphFilter,
            this.imageFilter,
            this.listFilter,
            this.tableFilter));
        configuration.setFilters(filters);
        
        return configuration;
    }    
}
