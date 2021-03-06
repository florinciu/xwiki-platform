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
package org.xwiki.query.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryExecutor;

/**
 * Stores all information needed for execute a query.
 *
 * @version $Id$
 * @since 1.6M1
 */
public class DefaultQuery implements Query
{
    /**
     * field for {@link Query#getStatement()}.
     */
    private String statement;

    /**
     * field for {@link Query#getLanguage()}.
     */
    private String language;

    /**
     * virtual wiki to run the query.
     */
    private String wiki;

    /**
     * map from query parameters to values.
     */
    private Map<String, Object> namedParameters = new HashMap<String, Object>();

    /**
     * map from index to positional parameter value.
     */
    private Map<Integer, Object> positionalParameters = new HashMap<Integer, Object>();

    /**
     * field for {@link Query#setLimit(int)}.
     */
    private int limit;

    /**
     * field for {@link Query#setOffset(int)}.
     */
    private int offset;

    /**
     * field for {@link #isNamed()}.
     */
    private boolean isNamed;

    /**
     * field for {@link #getExecuter()}.
     */
    private QueryExecutor executer;

    /**
     * Create a Query.
     *
     * @param statement query statement
     * @param language query language
     * @param executor QueryExecutor component for execute the query.
     */
    public DefaultQuery(String statement, String language, QueryExecutor executor)
    {
        this.statement = statement;
        this.language = language;
        this.executer = executor;
        this.isNamed = false;
    }

    /**
     * Create a named Query.
     *
     * @param queryName name of the query.
     * @param executor QueryExecutor component for execute the query.
     */
    public DefaultQuery(String queryName, QueryExecutor executor)
    {
        this.statement = queryName;
        this.executer = executor;
        this.isNamed = true;
    }

    /**
     * {@inheritDoc}
     */
    public String getStatement()
    {
        return statement;
    }

    /**
     * {@inheritDoc}
     */
    public String getLanguage()
    {
        return language;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isNamed()
    {
        return isNamed;
    }

    /**
     * {@inheritDoc}
     */
    public Query setWiki(String wiki)
    {
        this.wiki = wiki;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public String getWiki()
    {
        return wiki;
    }

    /**
     * {@inheritDoc}
     */
    public Query bindValue(String var, Object val)
    {
        namedParameters.put(var, val);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public Query bindValue(int index, Object val)
    {
        positionalParameters.put(index, val);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public Query bindValues(List<Object> values)
    {
        for (int i = 0; i < values.size(); i++) {
            bindValue(i + 1, values.get(i));
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public int getLimit()
    {
        return limit;
    }

    /**
     * {@inheritDoc}
     */
    public int getOffset()
    {
        return offset;
    }

    /**
     * {@inheritDoc}
     */
    public Query setLimit(int limit)
    {
        this.limit = limit;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public Query setOffset(int offset)
    {
        this.offset = offset;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, Object> getNamedParameters()
    {
        return namedParameters;
    }

    /**
     * {@inheritDoc}
     */
    public Map<Integer, Object> getPositionalParameters()
    {
        return positionalParameters;
    }

    /**
     * {@inheritDoc}
     */
    public <T> List<T> execute() throws QueryException
    {
        return getExecuter().execute(this);
    }

    /**
     * @return QueryExecutor interface for execute the query.
     */
    protected QueryExecutor getExecuter()
    {
        return executer;
    }
}
