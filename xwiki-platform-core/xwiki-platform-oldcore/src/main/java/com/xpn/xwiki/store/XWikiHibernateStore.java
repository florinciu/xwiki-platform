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
package com.xpn.xwiki.store;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.UUID;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.EntityMode;
import org.hibernate.FlushMode;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Settings;
import org.hibernate.connection.ConnectionProvider;
import org.hibernate.impl.SessionFactoryImpl;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Requirement;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.XWikiLink;
import com.xpn.xwiki.doc.XWikiLock;
import com.xpn.xwiki.monitor.api.MonitorPlugin;
import com.xpn.xwiki.objects.BaseCollection;
import com.xpn.xwiki.objects.BaseElement;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.BaseStringProperty;
import com.xpn.xwiki.objects.DBStringListProperty;
import com.xpn.xwiki.objects.DoubleProperty;
import com.xpn.xwiki.objects.FloatProperty;
import com.xpn.xwiki.objects.IntegerProperty;
import com.xpn.xwiki.objects.LargeStringProperty;
import com.xpn.xwiki.objects.ListProperty;
import com.xpn.xwiki.objects.LongProperty;
import com.xpn.xwiki.objects.PropertyInterface;
import com.xpn.xwiki.objects.StringListProperty;
import com.xpn.xwiki.objects.StringProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.DBListClass;
import com.xpn.xwiki.objects.classes.ListClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StaticListClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;
import com.xpn.xwiki.render.XWikiRenderer;
import com.xpn.xwiki.stats.impl.XWikiStats;
import com.xpn.xwiki.util.Util;
import com.xpn.xwiki.web.Utils;

/**
 * The XWiki Hibernate database driver.
 * 
 * @version $Id$
 */
@Component
public class XWikiHibernateStore extends XWikiHibernateBaseStore implements XWikiStoreInterface
{
    private static final Log log = LogFactory.getLog(XWikiHibernateStore.class);

    private Map<String, String[]> validTypesMap = new HashMap<String, String[]>();

    /**
     * QueryManager for this store.
     */
    @Requirement
    private QueryManager queryManager;

    /**
     * Used to convert a string into a proper Document Reference.
     */
    @SuppressWarnings("unchecked")
    private DocumentReferenceResolver<String> currentDocumentReferenceResolver =
        Utils.getComponent(DocumentReferenceResolver.class, "current");

    /**
     * Used to resolve a string into a proper Document Reference using the current document's reference to fill the
     * blanks, except for the page name for which the default page name is used instead and for the wiki name for which
     * the current wiki is used instead of the current document reference's wiki.
     */
    @SuppressWarnings("unchecked")
    private DocumentReferenceResolver<String> currentMixedDocumentReferenceResolver =
        Utils.getComponent(DocumentReferenceResolver.class, "currentmixed");

    /**
     * Used to convert a proper Document Reference to string (standard form).
     */
    @SuppressWarnings("unchecked")
    private EntityReferenceSerializer<String> defaultEntityReferenceSerializer =
        Utils.getComponent(EntityReferenceSerializer.class);

    /**
     * Used to convert a Document Reference to string (compact form without the wiki part).
     */
    @SuppressWarnings("unchecked")
    private EntityReferenceSerializer<String> compactWikiEntityReferenceSerializer =
        Utils.getComponent(EntityReferenceSerializer.class, "compactwiki");

    /**
     * Used to convert a proper Document Reference to a string but without the wiki name.
     */
    @SuppressWarnings("unchecked")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer =
        Utils.getComponent(EntityReferenceSerializer.class, "local");

    /**
     * This allows to initialize our storage engine. The hibernate config file path is taken from xwiki.cfg or directly
     * in the WEB-INF directory.
     * 
     * @param xwiki
     * @param context
     * @deprecated 1.6M1. Use ComponentManager.lookup(XWikiStoreInterface.class) instead.
     */
    @Deprecated
    public XWikiHibernateStore(XWiki xwiki, XWikiContext context)
    {
        super(xwiki, context);
        initValidColumTypes();
    }

    /**
     * Initialize the storage engine with a specific path. This is used for tests.
     * 
     * @param hibpath
     * @deprecated 1.6M1. Use ComponentManager.lookup(XWikiStoreInterface.class) instead.
     */
    @Deprecated
    public XWikiHibernateStore(String hibpath)
    {
        super(hibpath);
        initValidColumTypes();
    }

    /**
     * @see #XWikiHibernateStore(XWiki, XWikiContext)
     * @deprecated 1.6M1. Use ComponentManager.lookup(XWikiStoreInterface.class) instead.
     */
    @Deprecated
    public XWikiHibernateStore(XWikiContext context)
    {
        this(context.getWiki(), context);
    }

    /**
     * Empty constructor needed for component manager.
     */
    public XWikiHibernateStore()
    {
        initValidColumTypes();
    }

    /**
     * This initializes the valid custom types Used for Custom Mapping
     */
    private void initValidColumTypes()
    {
        String[] string_types = {"string", "text", "clob"};
        String[] number_types =
            {"integer", "long", "float", "double", "big_decimal", "big_integer", "yes_no", "true_false"};
        String[] date_types = {"date", "time", "timestamp"};
        String[] boolean_types = {"boolean", "yes_no", "true_false", "integer"};
        this.validTypesMap = new HashMap<String, String[]>();
        this.validTypesMap.put("com.xpn.xwiki.objects.classes.StringClass", string_types);
        this.validTypesMap.put("com.xpn.xwiki.objects.classes.TextAreaClass", string_types);
        this.validTypesMap.put("com.xpn.xwiki.objects.classes.PasswordClass", string_types);
        this.validTypesMap.put("com.xpn.xwiki.objects.classes.NumberClass", number_types);
        this.validTypesMap.put("com.xpn.xwiki.objects.classes.DateClass", date_types);
        this.validTypesMap.put("com.xpn.xwiki.objects.classes.BooleanClass", boolean_types);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#isWikiNameAvailable(java.lang.String, com.xpn.xwiki.XWikiContext)
     */
    public boolean isWikiNameAvailable(String wikiName, XWikiContext context) throws XWikiException
    {
        boolean available;

        boolean bTransaction = true;
        String database = context.getDatabase();

        try {
            bTransaction = beginTransaction(context);
            Session session = getSession(context);

            context.setDatabase(wikiName);
            try {
                setDatabase(session, context);
                available = false;
            } catch (XWikiException e) {
                // Failed to switch to database. Assume it means database does not exists.
                available = true;
            }
        } catch (Exception e) {
            Object[] args = {wikiName};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CHECK_EXISTS_DATABASE,
                "Exception while listing databases to search for {0}", e, args);
        } finally {
            context.setDatabase(database);
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }

        return available;
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#createWiki(java.lang.String, com.xpn.xwiki.XWikiContext)
     */
    public void createWiki(String wikiName, XWikiContext context) throws XWikiException
    {
        boolean bTransaction = true;
        String database = context.getDatabase();
        Statement stmt = null;
        try {
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Connection connection = session.connection();
            stmt = connection.createStatement();

            String schema = getSchemaFromWikiName(wikiName, context);
            String escapedSchema = escapeSchema(schema, context);

            DatabaseProduct databaseProduct = getDatabaseProductName(context);
            if (DatabaseProduct.ORACLE == databaseProduct) {
                stmt.execute("create user " + escapedSchema + " identified by " + escapedSchema);
                stmt.execute("grant resource to " + escapedSchema);
            } else if (DatabaseProduct.DERBY == databaseProduct) {
                stmt.execute("CREATE SCHEMA " + escapedSchema);
            } else if (DatabaseProduct.HSQLDB == databaseProduct) {
                stmt.execute("CREATE SCHEMA " + escapedSchema + " AUTHORIZATION DBA");
            } else if (DatabaseProduct.DB2 == databaseProduct) {
                stmt.execute("CREATE SCHEMA " + escapedSchema);
            } else if (DatabaseProduct.MYSQL == databaseProduct) {
                // TODO: find a proper java lib to convert from java encoding to mysql charset name and collation
                if (context.getWiki().getEncoding().equals("UTF-8")) {
                    stmt.execute("create database " + escapedSchema + " CHARACTER SET utf8 COLLATE utf8_bin");
                } else {
                    stmt.execute("create database " + escapedSchema);
                }
            } else {
                stmt.execute("create database " + escapedSchema);
            }

            endTransaction(context, true);
        } catch (Exception e) {
            Object[] args = {wikiName};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CREATE_DATABASE, "Exception while create wiki database {0}",
                e, args);
        } finally {
            context.setDatabase(database);
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (Exception e) {
            }
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#deleteWiki(java.lang.String, com.xpn.xwiki.XWikiContext)
     */
    public void deleteWiki(String wikiName, XWikiContext context) throws XWikiException
    {
        boolean bTransaction = true;
        String database = context.getDatabase();
        Statement stmt = null;
        try {
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Connection connection = session.connection();
            stmt = connection.createStatement();

            String schema = getSchemaFromWikiName(wikiName, context);
            String escapedSchema = escapeSchema(schema, context);

            DatabaseProduct databaseProduct = getDatabaseProductName(context);
            if (DatabaseProduct.ORACLE == databaseProduct) {
                stmt.execute("DROP USER " + escapedSchema + " CASCADE");
            } else if (DatabaseProduct.DERBY == databaseProduct) {
                stmt.execute("DROP SCHEMA " + escapedSchema);
            } else if (DatabaseProduct.HSQLDB == databaseProduct) {
                stmt.execute("DROP SCHEMA " + escapedSchema);
            } else if (DatabaseProduct.DB2 == databaseProduct) {
                stmt.execute("DROP SCHEMA " + escapedSchema + " RESTRICT");
            } else if (DatabaseProduct.MYSQL == databaseProduct) {
                stmt.execute("DROP DATABASE " + escapedSchema);
            }

            endTransaction(context, true);
        } catch (Exception e) {
            Object[] args = {wikiName};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETE_DATABASE, "Exception while delete wiki database {0}",
                e, args);
        } finally {
            context.setDatabase(database);
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (Exception e) {
            }
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    /**
     * Verifies if a wiki document exists
     * 
     * @param doc
     * @param context
     * @return
     * @throws XWikiException
     */
    public boolean exists(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        boolean bTransaction = true;
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {

            doc.setStore(this);
            checkHibernate(context);

            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate");
            }

            bTransaction = bTransaction && beginTransaction(false, context);
            Session session = getSession(context);
            String fullName = doc.getFullName();

            String sql = "select doc.fullName from XWikiDocument as doc where doc.fullName=:fullName";
            if (monitor != null) {
                monitor.setTimerDesc("hibernate", sql);
            }
            Query query = session.createQuery(sql);
            query.setString("fullName", fullName);
            Iterator<String> it = query.list().iterator();
            while (it.hasNext()) {
                if (fullName.equals(it.next())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Object[] args = {doc.getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CHECK_EXISTS_DOC, "Exception while reading document {0}", e,
                args);
        } finally {
            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }

            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
    }

    public void saveXWikiDoc(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate");
            }
            doc.setStore(this);
            // Make sure the database name is stored
            doc.getDocumentReference().setWikiReference(new WikiReference(context.getDatabase()));

            if (bTransaction) {
                checkHibernate(context);
                SessionFactory sfactory = injectCustomMappingsInSessionFactory(doc, context);
                bTransaction = beginTransaction(sfactory, context);
            }
            Session session = getSession(context);
            session.setFlushMode(FlushMode.COMMIT);

            // These informations will allow to not look for attachments and objects on loading
            doc.setElement(XWikiDocument.HAS_ATTACHMENTS, (doc.getAttachmentList().size() != 0));
            doc.setElement(XWikiDocument.HAS_OBJECTS, (doc.getXObjects().size() != 0));

            // Let's update the class XML since this is the new way to store it
            // TODO If all the properties are removed, the old xml stays?
            BaseClass bclass = doc.getXClass();
            if (bclass != null) {
                if (bclass.getFieldList().size() > 0) {
                    doc.setXClassXML(bclass.toXMLString());
                } else {
                    doc.setXClassXML("");
                }
            }

            if (doc.hasElement(XWikiDocument.HAS_ATTACHMENTS)) {
                saveAttachmentList(doc, context, false);
            }

            // Handle the latest text file
            if (doc.isContentDirty() || doc.isMetaDataDirty()) {
                Date ndate = new Date();
                doc.setDate(ndate);
                if (doc.isContentDirty()) {
                    doc.setContentUpdateDate(ndate);
                    doc.setContentAuthor(doc.getAuthor());
                }
                doc.incrementVersion();
                if (context.getWiki().hasVersioning(context)) {
                    context.getWiki().getVersioningStore().updateXWikiDocArchive(doc, false, context);
                }

                doc.setContentDirty(false);
                doc.setMetaDataDirty(false);
            } else {
                if (doc.getDocumentArchive() != null) {
                    // Let's make sure we save the archive if we have one
                    // This is especially needed if we load a document from XML
                    if (context.getWiki().hasVersioning(context)) {
                        context.getWiki().getVersioningStore().saveXWikiDocArchive(doc.getDocumentArchive(), false,
                            context);
                    }
                } else {
                    // Make sure the getArchive call has been made once
                    // with a valid context
                    try {
                        if (context.getWiki().hasVersioning(context)) {
                            doc.getDocumentArchive(context);
                        }
                    } catch (XWikiException e) {
                        // this is a non critical error
                    }
                }
            }

            // Verify if the document already exists
            Query query = session.createQuery(
                "select xwikidoc.id from XWikiDocument as xwikidoc where xwikidoc.id = :id");
            query.setLong("id", doc.getId());
            if (query.uniqueResult() == null) {
                session.save(doc);
            } else {
                session.update(doc);
                // TODO: this is slower!! How can it be improved?
                // session.saveOrUpdate(doc);
            }

            // Remove objects planned for removal
            if (doc.getXObjectsToRemove().size() > 0) {
                for (BaseObject removedObject : doc.getXObjectsToRemove()) {
                    deleteXWikiObject(removedObject, context, false);
                }
                doc.setXObjectsToRemove(new ArrayList<BaseObject>());
            }

            // We should only save the class if we are using the class table mode
            if (bclass != null) {
                bclass.setDocumentReference(doc.getDocumentReference());
                if ((bclass.getFieldList().size() > 0) && (useClassesTable(true, context))) {
                    saveXWikiClass(bclass, context, false);
                }
                // Store this XWikiClass in the context so that we can use it in case of recursive
                // usage of classes
                context.addBaseClass(bclass);
                // update objects of the class
                for (Iterator itf = bclass.getFieldList().iterator(); itf.hasNext();) {
                    PropertyClass prop = (PropertyClass) itf.next();
                    // migrate values of list properties
                    if (prop instanceof StaticListClass || prop instanceof DBListClass) {
                        ListClass lc = (ListClass) prop;
                        String[] classes = {DBStringListProperty.class.getName(), StringListProperty.class.getName(),
                            StringProperty.class.getName()}; // @see ListClass#newProperty()
                        for (int i = 0; i < classes.length; i++) {
                            String oldclass = classes[i];
                            if (!oldclass.equals(lc.newProperty().getClass().getName())) {
                                Query q = session.createQuery("select p from " + oldclass + " as p, BaseObject as o"
                                    + " where o.className=? and p.id=o.id and p.name=?").setString(0,
                                    bclass.getName()).setString(1, lc.getName());
                                for (Iterator it = q.list().iterator(); it.hasNext();) {
                                    BaseProperty lp = (BaseProperty) it.next();
                                    BaseProperty lp1 = lc.newProperty();
                                    lp1.setId(lp.getId());
                                    lp1.setName(lp.getName());
                                    if (lc.isMultiSelect()) {
                                        List tmp;
                                        if (lp.getValue() instanceof List) {
                                            tmp = (List) lp.getValue();
                                        } else {
                                            tmp = new ArrayList(1);
                                            tmp.add(lp.getValue());
                                        }
                                        lp1.setValue(tmp);
                                    } else {
                                        Object tmp = lp.getValue();
                                        if (tmp instanceof List && ((List) tmp).size() > 0) {
                                            tmp = ((List) tmp).get(0);
                                        }
                                        lp1.setValue(tmp);
                                    }
                                    session.delete(lp);
                                    session.save(lp1);
                                }
                            }
                        }
                    }
                    // migrate values of list properties
                    else if (prop instanceof NumberClass) {
                        NumberClass nc = (NumberClass) prop;
                        // @see NumberClass#newProperty()
                        String[] classes =
                            {IntegerProperty.class.getName(), LongProperty.class.getName(),
                            FloatProperty.class.getName(), DoubleProperty.class.getName()};
                        for (int i = 0; i < classes.length; i++) {
                            String oldclass = classes[i];
                            if (!oldclass.equals(nc.newProperty().getClass().getName())) {
                                Query q = session.createQuery(
                                    "select p from " + oldclass + " as p, BaseObject as o" + " where o.className=?"
                                    + "  and p.id=o.id and p.name=?").setString(0, bclass.getName()).setString(
                                    1, nc.getName());
                                for (BaseProperty np : (List<BaseProperty>) q.list()) {
                                    BaseProperty np1 = nc.newProperty();
                                    np1.setId(np.getId());
                                    np1.setName(np.getName());
                                    if (nc.getNumberType().equals("integer")) {
                                        np1.setValue(Integer.valueOf(((Number) np.getValue()).intValue()));
                                    } else if (nc.getNumberType().equals("float")) {
                                        np1.setValue(Float.valueOf(((Number) np.getValue()).floatValue()));
                                    } else if (nc.getNumberType().equals("double")) {
                                        np1.setValue(Double.valueOf(((Number) np.getValue()).doubleValue()));
                                    } else if (nc.getNumberType().equals("long")) {
                                        np1.setValue(Long.valueOf(((Number) np.getValue()).longValue()));
                                    }
                                    session.delete(np);
                                    session.save(np1);
                                }
                            }
                        }
                    }
                }
            } else {
                // TODO: Remove existing class
            }

            if (doc.hasElement(XWikiDocument.HAS_OBJECTS)) {
                // TODO: Delete all objects for which we don't have a name in the Map
                for (List<BaseObject> objects : doc.getXObjects().values()) {
                    for (BaseObject obj : objects) {
                        if (obj != null) {
                            obj.setDocumentReference(doc.getDocumentReference());
                            /* If the object doesn't have a GUID, create it before saving */
                            if (StringUtils.isEmpty(obj.getGuid())) {
                                obj.setGuid(UUID.randomUUID().toString());
                            }
                            saveXWikiCollection(obj, context, false);
                        }
                    }
                }
            }

            if (context.getWiki().hasBacklinks(context)) {
                saveLinks(doc, context, true);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }

            doc.setNew(false);

            // We need to ensure that the saved document becomes the original document
            doc.setOriginalDocument(doc.clone());

        } catch (Exception e) {
            Object[] args = {this.defaultEntityReferenceSerializer.serialize(doc.getDocumentReference())};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, "Exception while saving document {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }
    }

    public void saveXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        saveXWikiDoc(doc, context, true);
    }

    public XWikiDocument loadXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        // To change body of implemented methods use Options | File Templates.
        boolean bTransaction = true;
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate");
            }
            doc.setStore(this);
            checkHibernate(context);

            SessionFactory sfactory = injectCustomMappingsInSessionFactory(doc, context);
            bTransaction = bTransaction && beginTransaction(sfactory, false, context);
            Session session = getSession(context);
            session.setFlushMode(FlushMode.MANUAL);

            try {
                session.load(doc, new Long(doc.getId()));
                doc.setDatabase(context.getDatabase());
                doc.setNew(false);
                doc.setMostRecent(true);
                // Fix for XWIKI-1651
                doc.setDate(new Date(doc.getDate().getTime()));
                doc.setCreationDate(new Date(doc.getCreationDate().getTime()));
                doc.setContentUpdateDate(new Date(doc.getContentUpdateDate().getTime()));
            } catch (ObjectNotFoundException e) { // No document
                doc.setNew(true);
                return doc;
            }

            // Loading the attachment list
            if (doc.hasElement(XWikiDocument.HAS_ATTACHMENTS)) {
                loadAttachmentList(doc, context, false);
            }

            // TODO: handle the case where there are no xWikiClass and xWikiObject in the Database
            BaseClass bclass = new BaseClass();
            String cxml = doc.getXClassXML();
            if (cxml != null) {
                bclass.fromXML(cxml);
                bclass.setDocumentReference(doc.getDocumentReference());
                doc.setXClass(bclass);
            } else if (useClassesTable(false, context)) {
                bclass.setDocumentReference(doc.getDocumentReference());
                bclass = loadXWikiClass(bclass, context, false);
                doc.setXClass(bclass);
            }

            // Store this XWikiClass in the context so that we can use it in case of recursive usage
            // of classes
            context.addBaseClass(bclass);

            if (doc.hasElement(XWikiDocument.HAS_OBJECTS)) {
                Query query = session.createQuery("from BaseObject as bobject where bobject.name = :name order by "
                    + "bobject.number");
                query.setText("name", doc.getFullName());
                Iterator it = query.list().iterator();

                EntityReference localGroupEntityReference = new EntityReference("XWikiGroups", EntityType.DOCUMENT,
                    new EntityReference("XWiki", EntityType.SPACE));
                DocumentReference groupsDocumentReference = new DocumentReference(context.getDatabase(),
                    localGroupEntityReference.getParent().getName(), localGroupEntityReference.getName());

                boolean hasGroups = false;
                while (it.hasNext()) {
                    BaseObject object = (BaseObject) it.next();
                    DocumentReference classReference = object.getXClassReference();

                    if (classReference == null) {
                        continue;
                    }

                    // It seems to search before is case insensitive. And this would break the loading if we get an
                    // object which doesn't really belong to this document
                    if (!object.getDocumentReference().equals(doc.getDocumentReference())) {
                        continue;
                    }

                    BaseObject newobject;
                    if (classReference.equals(doc.getDocumentReference())) {
                        newobject = bclass.newCustomClassInstance(context);
                    } else {
                        newobject = BaseClass.newCustomClassInstance(classReference, context);
                    }
                    if (newobject != null) {
                        newobject.setId(object.getId());
                        newobject.setClassName(object.getClassName());
                        newobject.setDocumentReference(object.getDocumentReference());
                        newobject.setNumber(object.getNumber());
                        newobject.setGuid(object.getGuid());
                        object = newobject;
                    }

                    if (classReference.equals(groupsDocumentReference)) {
                        // Groups objects are handled differently.
                        hasGroups = true;
                    } else {
                        loadXWikiCollection(object, doc, context, false, true);
                    }
                    doc.setXObject(object.getNumber(), object);
                }

                // AFAICT this was added as an emergency patch because loading of objects has proven
                // too slow and the objects which cause the most overhead are the XWikiGroups objects
                // as each group object (each group member) would otherwise cost 2 database queries.
                // This will do every group member in a single query.
                if (hasGroups) {
                    Query query2 = session.createQuery("select bobject.number, prop.value from StringProperty as prop, "
                        + "BaseObject as bobject where bobject.name = :name and bobject.className='XWiki.XWikiGroups' "
                        + "and bobject.id=prop.id.id and prop.id.name='member' order by bobject.number");
                    query2.setText("name", doc.getFullName());
                    Iterator it2 = query2.list().iterator();
                    while (it2.hasNext()) {
                        Object[] result = (Object[]) it2.next();
                        Integer number = (Integer) result[0];
                        String member = (String) result[1];
                        BaseObject obj = BaseClass.newCustomClassInstance(groupsDocumentReference, context);
                        obj.setDocumentReference(doc.getDocumentReference());
                        obj.setXClassReference(localGroupEntityReference);
                        obj.setNumber(number.intValue());
                        obj.setStringValue("member", member);
                        doc.setXObject(obj.getNumber(), obj);
                    }
                }
            }

            // We need to ensure that the loaded document becomes the original document
            doc.setOriginalDocument(doc.clone());

            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            Object[] args = {doc.getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_READING_DOC, "Exception while reading document {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }

        log.debug("Loaded XWikiDocument: " + doc.getFullName());

        return doc;
    }

    public void deleteXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        boolean bTransaction = true;
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate");
            }
            checkHibernate(context);
            SessionFactory sfactory = injectCustomMappingsInSessionFactory(doc, context);
            bTransaction = bTransaction && beginTransaction(sfactory, context);
            Session session = getSession(context);
            session.setFlushMode(FlushMode.COMMIT);

            if (doc.getStore() == null) {
                Object[] args = {doc.getFullName()};
                throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                    XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CANNOT_DELETE_UNLOADED_DOC,
                    "Impossible to delete document {0} if it is not loaded", null, args);
            }

            // Let's delete any attachment this document might have
            List attachlist = doc.getAttachmentList();
            for (int i = 0; i < attachlist.size(); i++) {
                XWikiAttachment attachment = (XWikiAttachment) attachlist.get(i);
                context.getWiki().getAttachmentStore().deleteXWikiAttachment(attachment, false, context, false);
            }

            // deleting XWikiLinks
            if (context.getWiki().hasBacklinks(context)) {
                deleteLinks(doc.getId(), context, true);
            }

            BaseClass bclass = doc.getXClass();
            if ((bclass.getFieldList().size() > 0) && (useClassesTable(true, context))) {
                deleteXWikiClass(bclass, context, false);
            }

            // Find the list of classes for which we have an object
            // Remove properties planned for removal
            if (doc.getObjectsToRemove().size() > 0) {
                for (int i = 0; i < doc.getObjectsToRemove().size(); i++) {
                    BaseObject bobj = doc.getObjectsToRemove().get(i);
                    if (bobj != null) {
                        deleteXWikiObject(bobj, context, false);
                    }
                }
                doc.setObjectsToRemove(new ArrayList<BaseObject>());
            }
            for (List<BaseObject> objects : doc.getXObjects().values()) {
                for (BaseObject obj : objects) {
                    if (obj != null) {
                        deleteXWikiObject(obj, context, false);
                    }
                }
            }
            context.getWiki().getVersioningStore().deleteArchive(doc, false, context);

            session.delete(doc);

            // We need to ensure that the deleted document becomes the original document
            doc.setOriginalDocument(doc.clone());

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = {doc.getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_DOC, "Exception while deleting document {0}", e,
                args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void saveXWikiObject(BaseObject object, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        saveXWikiCollection(object, context, bTransaction);
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void saveXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        try {
            if (object == null) {
                return;
            }
            // We need a slightly different behavior here
            boolean stats = (object instanceof XWikiStats);

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // Verify if the property already exists
            Query query;
            if (stats) {
                query =
                    session.createQuery("select obj.id from " + object.getClass().getName()
                    + " as obj where obj.id = :id");
            } else {
                query = session.createQuery("select obj.id from BaseObject as obj where obj.id = :id");
            }
            query.setInteger("id", object.getId());
            if (query.uniqueResult() == null) {
                if (stats) {
                    session.save(object);
                } else {
                    session.save("com.xpn.xwiki.objects.BaseObject", object);
                }
            } else {
                if (stats) {
                    session.update(object);
                } else {
                    session.update("com.xpn.xwiki.objects.BaseObject", object);
                }
            }
            /*
             * if (stats) session.saveOrUpdate(object); else
             * session.saveOrUpdate((String)"com.xpn.xwiki.objects.BaseObject", (Object)object);
             */
            BaseClass bclass = object.getXClass(context);
            List<String> handledProps = new ArrayList<String>();
            if ((bclass != null) && (bclass.hasCustomMapping()) && context.getWiki().hasCustomMappings()) {
                // save object using the custom mapping
                Map objmap = object.getCustomMappingMap();
                handledProps = bclass.getCustomMappingPropertyList(context);
                Session dynamicSession = session.getSession(EntityMode.MAP);
                query = session.createQuery("select obj.id from " + bclass.getName() + " as obj where obj.id = :id");
                query.setInteger("id", object.getId());
                if (query.uniqueResult() == null) {
                    dynamicSession.save(bclass.getName(), objmap);
                } else {
                    dynamicSession.update(bclass.getName(), objmap);
                }

                // dynamicSession.saveOrUpdate((String) bclass.getName(), objmap);
            }

            if (object.getXClassReference() != null) {
                // Remove all existing properties
                if (object.getFieldsToRemove().size() > 0) {
                    for (int i = 0; i < object.getFieldsToRemove().size(); i++) {
                        BaseProperty prop = (BaseProperty) object.getFieldsToRemove().get(i);
                        if (!handledProps.contains(prop.getName())) {
                            session.delete(prop);
                        }
                    }
                    object.setFieldsToRemove(new ArrayList<BaseProperty>());
                }

                Iterator it = object.getPropertyList().iterator();
                while (it.hasNext()) {
                    String key = (String) it.next();
                    BaseProperty prop = (BaseProperty) object.getField(key);
                    if (!prop.getName().equals(key)) {
                        Object[] args = {key, object.getName()};
                        throw new XWikiException(XWikiException.MODULE_XWIKI_CLASSES,
                            XWikiException.ERROR_XWIKI_CLASSES_FIELD_INVALID,
                            "Field {0} in object {1} has an invalid name", null, args);
                    }

                    String pname = prop.getName();
                    if (pname != null && !pname.trim().equals("") && !handledProps.contains(pname)) {
                        saveXWikiProperty(prop, context, false);
                    }
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (XWikiException xe) {
            throw xe;
        } catch (Exception e) {
            Object[] args = {object.getName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_OBJECT, "Exception while saving object {0}", e, args);

        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, true);
                }
            } catch (Exception e) {
            }
        }
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void loadXWikiObject(BaseObject object, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        loadXWikiCollection(object, null, context, bTransaction, false);
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void loadXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        loadXWikiCollection(object, null, context, bTransaction, false);
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void loadXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction,
        boolean alreadyLoaded) throws XWikiException
    {
        loadXWikiCollection(object, null, context, bTransaction, alreadyLoaded);
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void loadXWikiCollection(BaseCollection object1, XWikiDocument doc, XWikiContext context,
        boolean bTransaction, boolean alreadyLoaded) throws XWikiException
    {
        BaseCollection object = object1;
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            if (!alreadyLoaded) {
                try {
                    session.load(object, Integer.valueOf(object1.getId()));
                } catch (ObjectNotFoundException e) {
                    // There is no object data saved
                    object = null;
                    return;
                }
            }

            DocumentReference classReference = object.getXClassReference();

            // If the class reference is null in the loaded object then skip loading properties
            if (classReference != null) {

                BaseClass bclass = null;
                if (!classReference.equals(object.getDocumentReference())) {
                    // Let's check if the class has a custom mapping
                    bclass = object.getXClass(context);
                } else {
                    // We need to get it from the document otherwise
                    // we will go in an endless loop
                    if (doc != null) {
                        bclass = doc.getXClass();
                    }
                }

                List handledProps = new ArrayList();
                try {
                    if ((bclass != null) && (bclass.hasCustomMapping()) && context.getWiki().hasCustomMappings()) {
                        Session dynamicSession = session.getSession(EntityMode.MAP);
                        Object map = dynamicSession.load(bclass.getName(), Integer.valueOf(object.getId()));
                        // Let's make sure to look for null fields in the dynamic mapping
                        bclass.fromValueMap((Map) map, object);
                        handledProps = bclass.getCustomMappingPropertyList(context);
                        for (Iterator it = handledProps.iterator(); it.hasNext();) {
                            String prop = (String) it.next();
                            if (((Map) map).get(prop) == null) {
                                handledProps.remove(prop);
                            }
                        }
                    }
                } catch (Exception e) {
                }

                // Load strings, integers, dates all at once

                Query query = session.createQuery("select prop.name, prop.classType from BaseProperty as prop where "
                    + "prop.id.id = :id");
                query.setInteger("id", object.getId());
                for (Object[] result : (List<Object[]>) query.list()) {
                    String name = (String) result[0];
                    // No need to load fields already loaded from
                    // custom mapping
                    if (handledProps.contains(name)) {
                        continue;
                    }
                    String classType = (String) result[1];
                    BaseProperty property = null;

                    try {
                        property = (BaseProperty) Class.forName(classType).newInstance();
                        property.setObject(object);
                        property.setName(name);
                        loadXWikiProperty(property, context, false);
                    } catch (Exception e) {
                        // WORKAROUND IN CASE OF MIXMATCH BETWEEN STRING AND LARGESTRING
                        try {
                            if (property instanceof StringProperty) {
                                LargeStringProperty property2 = new LargeStringProperty();
                                property2.setObject(object);
                                property2.setName(name);
                                loadXWikiProperty(property2, context, false);
                                property.setValue(property2.getValue());

                                if (bclass != null) {
                                    if (bclass.get(name) instanceof TextAreaClass) {
                                        property = property2;
                                    }
                                }

                            } else if (property instanceof LargeStringProperty) {
                                StringProperty property2 = new StringProperty();
                                property2.setObject(object);
                                property2.setName(name);
                                loadXWikiProperty(property2, context, false);
                                property.setValue(property2.getValue());

                                if (bclass != null) {
                                    if (bclass.get(name) instanceof StringClass) {
                                        property = property2;
                                    }
                                }
                            } else {
                                throw e;
                            }
                        } catch (Throwable e2) {
                            Object[] args =
                                {object.getName(), object.getClass(), Integer.valueOf(object.getNumber() + ""), name};
                            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                                "Exception while loading object '{0}' of class '{1}', number '{2}' and property '{3}'",
                                e, args);
                        }
                    }

                    object.addField(name, property);
                }
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            Object[] args = {object.getName(), object.getClass(), Integer.valueOf(object.getNumber() + "")};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                "Exception while loading object '{0}' of class '{1}' and number '{2}'", e, args);

        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }

    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void deleteXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        deleteXWikiCollection(object, context, bTransaction, false);
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void deleteXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction, boolean evict)
        throws XWikiException
    {
        if (object == null) {
            return;
        }
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // Let's check if the class has a custom mapping
            BaseClass bclass = object.getXClass(context);
            List handledProps = new ArrayList();
            if ((bclass != null) && (bclass.hasCustomMapping()) && context.getWiki().hasCustomMappings()) {
                handledProps = bclass.getCustomMappingPropertyList(context);
                Session dynamicSession = session.getSession(EntityMode.MAP);
                Object map = dynamicSession.get(bclass.getName(), Integer.valueOf(object.getId()));
                if (map != null) {
                    if (evict) {
                        dynamicSession.evict(map);
                    }
                    dynamicSession.delete(map);
                }
            }

            if (object.getXClassReference() != null) {
                for (Iterator it = object.getFieldList().iterator(); it.hasNext();) {
                    BaseElement property = (BaseElement) it.next();
                    if (!handledProps.contains(property.getName())) {
                        if (evict) {
                            session.evict(property);
                        }
                        if (session.get(property.getClass(), property) != null) {
                            session.delete(property);
                        }
                    }
                }
            }

            // In case of custom class we need to force it as BaseObject to delete the xwikiobject row
            if (!"".equals(bclass.getCustomClass())) {
                BaseObject cobject = new BaseObject();
                cobject.setDocumentReference(object.getDocumentReference());
                cobject.setClassName(object.getClassName());
                cobject.setNumber(object.getNumber());
                if (object instanceof BaseObject) {
                    cobject.setGuid(((BaseObject) object).getGuid());
                }
                cobject.setId(object.getId());
                if (evict) {
                    session.evict(cobject);
                }
                session.delete(cobject);
            } else {
                if (evict) {
                    session.evict(object);
                }
                session.delete(object);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = {object.getName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_OBJECT, "Exception while deleting object {0}", e,
                args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void deleteXWikiObject(BaseObject baseObject, XWikiContext context, boolean bTransaction, boolean bEvict)
        throws XWikiException
    {
        deleteXWikiCollection(baseObject, context, bTransaction, bEvict);
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void deleteXWikiObject(BaseObject baseObject, XWikiContext context, boolean b) throws XWikiException
    {
        deleteXWikiCollection(baseObject, context, b);
    }

    private void deleteXWikiClass(BaseClass baseClass, XWikiContext context, boolean b) throws XWikiException
    {
        deleteXWikiCollection(baseClass, context, b);
    }

    private void loadXWikiProperty(PropertyInterface property, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            try {
                session.load(property, (Serializable) property);
                // In Oracle, empty string are converted to NULL. Since an undefined property is not found at all, it is
                // safe to assume that a retrieved NULL value should actually be an empty string.
                if (property instanceof BaseStringProperty) {
                    BaseStringProperty stringProperty = (BaseStringProperty) property;
                    if (stringProperty.getValue() == null) {
                        stringProperty.setValue("");
                    }
                }
            } catch (ObjectNotFoundException e) {
                // Let's accept that there is no data in property tables
                // but log it
                if (log.isErrorEnabled()) {
                    log.error("No data for property " + property.getName() + " of object id " + property.getId());
                }
            }

            // TODO: understand why collections are lazy loaded
            // Let's force reading lists if there is a list
            // This seems to be an issue since Hibernate 3.0
            // Without this test ViewEditTest.testUpdateAdvanceObjectProp fails
            if (property instanceof ListProperty) {
                ((ListProperty) property).getList();
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            BaseCollection obj = property.getObject();
            Object[] args = {(obj != null) ? obj.getName() : "unknown", property.getName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                "Exception while loading property {1} of object {0}", e, args);

        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
    }

    /**
     * @deprecated This is internal to XWikiHibernateStore and may be removed in the future.
     */
    @Deprecated
    public void saveXWikiProperty(final PropertyInterface property,
                                  final XWikiContext context,
                                  final boolean runInOwnTransaction)
        throws XWikiException
    {
        // Clone runInOwnTransaction so the value passed is not altered.
        boolean bTransaction = runInOwnTransaction;
        try {
            if (bTransaction) {
                this.checkHibernate(context);
                bTransaction = this.beginTransaction(context);
            }

            final Session session = this.getSession(context);

            final Query query = session.createQuery(
                "select prop.name from BaseProperty as prop where prop.id.id = :id and prop.id.name= :name");
            query.setInteger("id", property.getId());
            query.setString("name", property.getName());

            if (query.uniqueResult() == null) {
                session.save(property);
            } else {
                session.update(property);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            // Something went wrong, collect some information.
            final BaseCollection obj = property.getObject();
            final Object[] args = {(obj != null) ? obj.getName() : "unknown", property.getName()};

            // Try to roll back the transaction if this is in it's own transaction.
            try {
                if (bTransaction) {
                    this.endTransaction(context, false);
                }
            } catch (Exception ee) {
                // Not a lot we can do here if there was an exception committing and an exception rolling back.
            }

            // Throw the exception.
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                                     XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                                     "Exception while saving property {1} of object {0}", e, args);
        }
    }

    private void saveXWikiClass(BaseClass bclass, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // Verify if the property already exists
            Query query = session.createQuery("select obj.id from BaseClass as obj where obj.id = :id");
            query.setInteger("id", bclass.getId());
            if (query.uniqueResult() == null) {
                session.save(bclass);
            } else {
                session.update(bclass);
            }

            // Remove all existing properties
            if (bclass.getFieldsToRemove().size() > 0) {
                for (int i = 0; i < bclass.getFieldsToRemove().size(); i++) {
                    session.delete(bclass.getFieldsToRemove().get(i));
                }
                bclass.setFieldsToRemove(new ArrayList());
            }

            Collection coll = bclass.getFieldList();
            Iterator it = coll.iterator();
            while (it.hasNext()) {
                PropertyClass prop = (PropertyClass) it.next();
                String pname = prop.getName();
                if (pname != null && !pname.trim().equals("")) {
                    saveXWikiClassProperty(prop, context, false);
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = {bclass.getName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_CLASS, "Exception while saving class {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private BaseClass loadXWikiClass(BaseClass bclass, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            try {
                session.load(bclass, Integer.valueOf(bclass.getId()));

                Query query =
                    session
                        .createQuery("select prop.name, prop.classType from PropertyClass as prop where prop.id.id = :id order by prop.number asc");
                query.setInteger("id", bclass.getId());
                Iterator it = query.list().iterator();
                while (it.hasNext()) {
                    Object obj = it.next();
                    Object[] result = (Object[]) obj;
                    String name = (String) result[0];
                    String classType = (String) result[1];
                    PropertyClass property = (PropertyClass) Class.forName(classType).newInstance();
                    property.setName(name);
                    property.setObject(bclass);
                    session.load(property, property);
                    bclass.addField(name, property);
                }
            } catch (ObjectNotFoundException e) {
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }

            if ((bclass != null) && (bclass.hasExternalCustomMapping())) {
                setSessionFactory(injectCustomMappingsInSessionFactory(bclass, context));
            }

            return bclass;
        } catch (Exception e) {
            Object[] args = {bclass.getName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_CLASS, "Exception while loading class {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private void saveXWikiClassProperty(PropertyClass property, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // I'm using a local transaction
            // There might be implications to this for a wider transaction
            Transaction ltransaction = session.beginTransaction();

            // Use to chose what to delete
            boolean isSave = false;
            try {
                Query query =
                    session
                        .createQuery("select prop.name from PropertyClass as prop where prop.id.id = :id and prop.id.name= :name");
                query.setInteger("id", property.getId());
                query.setString("name", property.getName());
                if (query.uniqueResult() == null) {
                    isSave = true;
                    session.save(property);
                } else {
                    isSave = false;
                    session.update(property);
                }

                session.flush();
                ltransaction.commit();
            } catch (Exception e) {
                // This seems to have failed..
                // This is an attempt to cleanup a potential mess
                // This code is only called if the tables are in an incoherent state
                // (Example: data in xwikiproperties and no data in xwikiintegers or vice-versa)
                // TODO: verify of the code works with longer transactions
                PropertyClass prop2;
                // Depending on save/update there is too much data either
                // in the BaseProperty table or in the inheritated property table
                // We need to delete this data
                if (isSave) {
                    prop2 = property;
                } else {
                    prop2 = new PropertyClass();
                }

                prop2.setName(property.getName());
                prop2.setObject(property.getObject());
                ltransaction.rollback();

                // We need to run the delete in a separate session
                // This is not a problem since this is cleaning up
                Session session2 = getSessionFactory().openSession();
                Transaction transaction2 = session2.beginTransaction();
                session2.delete(prop2);
                session2.flush();

                // I don't understand why I can't run this in the general session
                // This might make transactions fail
                if (!isSave) {
                    session2.save(property);
                }
                transaction2.commit();
                session2.close();
            }

            if (bTransaction) {
                endTransaction(context, true);
            }

        } catch (Exception e) {
            Object[] args = {property.getObject().getName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_CLASS, "Exception while saving class {0}", e, args);

        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private void loadAttachmentList(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("from XWikiAttachment as attach where attach.docId=:docid");
            query.setLong("docid", doc.getId());
            List<XWikiAttachment> list = query.list();
            for (XWikiAttachment attachment : list) {
                attachment.setDoc(doc);
            }
            doc.setAttachmentList(list);
            if (bTransaction) {
                endTransaction(context, false, false);
                bTransaction = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Object[] args = {doc.getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCHING_ATTACHMENT,
                "Exception while searching attachments for documents {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private void saveAttachmentList(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            getSession(context);

            List<XWikiAttachment> list = doc.getAttachmentList();
            for (XWikiAttachment attachment : list) {
                attachment.setDoc(doc);
                saveAttachment(attachment, false, context, false);
            }

            if (bTransaction) {
                // The session is closed here, too.
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = {doc.getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT_LIST,
                "Exception while saving attachments attachment list of document {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private void saveAttachment(XWikiAttachment attachment, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        saveAttachment(attachment, true, context, bTransaction);
    }

    private void saveAttachment(XWikiAttachment attachment, boolean parentUpdate, XWikiContext context,
        boolean bTransaction) throws XWikiException
    {
        try {
            // The version number must be bumped and the date must be set before the attachment
            // metadata is saved. Changing the version and date after calling
            // session.save()/session.update() "worked" (the altered version was what Hibernate saved)
            // but only if everything is done in the same transaction and as far as I know it
            // depended on undefined behavior.
            if (attachment.isContentDirty()) {
                attachment.updateContentArchive(context);
            }

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select attach.id from XWikiAttachment as attach where attach.id = :id");
            query.setLong("id", attachment.getId());
            if (query.uniqueResult() == null) {
                session.save(attachment);
            } else {
                session.update(attachment);
            }

            // If the attachment content is "dirty" (out of sync with the database)
            if (attachment.isContentDirty()) {
                // We must save the content of the attachment.
                // updateParent and bTransaction must be false because the content should be saved in the same
                // transation as the attachment and if the parent doc needs to be updated, this function will do it.
                context.getWiki().getAttachmentStore().saveAttachmentContent(attachment, false, context, false);
            }

            if (parentUpdate) {
                context.getWiki().getStore().saveXWikiDoc(attachment.getDoc(), context, false);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }

            // Mark the attachment content and metadata as not dirty.
            // Ideally this would only happen if the transaction is committed successfully but since an unsuccessful
            // transaction will most likely be accompanied by an exception, the cache will not have a chance to save
            // the copy of the document with erronious information. If this is not set here, the cache will return
            // a copy of the attachment which claims to be dirty although it isn't.
            attachment.setMetaDataDirty(false);
            if (attachment.isContentDirty()) {
                attachment.getAttachment_content().setContentDirty(false);
            }

        } catch (Exception e) {
            Object[] args = {attachment.getFilename(), attachment.getDoc().getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT,
                "Exception while saving attachments for attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    public XWikiLock loadLock(long docId, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        XWikiLock lock = null;
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select lock.docId from XWikiLock as lock where lock.docId = :docId");
            query.setLong("docId", docId);
            if (query.uniqueResult() != null) {
                lock = new XWikiLock();
                session.load(lock, new Long(docId));
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_LOCK, "Exception while loading lock", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
        return lock;
    }

    public void saveLock(XWikiLock lock, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select lock.docId from XWikiLock as lock where lock.docId = :docId");
            query.setLong("docId", lock.getDocId());
            if (query.uniqueResult() == null) {
                session.save(lock);
            } else {
                session.update(lock);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_LOCK, "Exception while locking document", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    public void deleteLock(XWikiLock lock, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            session.delete(lock);

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_LOCK, "Exception while deleting lock", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    public List<XWikiLink> loadLinks(long docId, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        List<XWikiLink> links = new ArrayList<XWikiLink>();
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery(" from XWikiLink as link where link.id.docId = :docId");
            query.setLong("docId", docId);

            links = query.list();

            if (bTransaction) {
                endTransaction(context, false, false);
                bTransaction = false;
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_LINKS, "Exception while loading links", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
        return links;
    }

    /**
     * @since 2.2M2
     */
    public List<DocumentReference> loadBacklinks(DocumentReference documentReference, boolean bTransaction,
        XWikiContext context) throws XWikiException
    {
        List<DocumentReference> backlinkReferences = new ArrayList<DocumentReference>();
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            // the select clause is compulsory to reach the fullName i.e. the page pointed
            Query query = session.createQuery("select backlink.fullName from XWikiLink as backlink where "
                + "backlink.id.link = :backlink");
            query.setString("backlink", this.localEntityReferenceSerializer.serialize(documentReference));

            List<String> backlinkNames = query.list();

            // Convert strings into references
            for (String backlinkName : backlinkNames) {
                backlinkReferences.add(this.currentMixedDocumentReferenceResolver.resolve(backlinkName));
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_BACKLINKS, "Exception while loading backlinks", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
        return backlinkReferences;
    }

    /**
     * @deprecated since 2.2M2 use {@link #loadBacklinks(DocumentReference, boolean, XWikiContext)}
     */
    @Deprecated
    public List<String> loadBacklinks(String fullName, XWikiContext context, boolean bTransaction)
        throws XWikiException
    {
        List<String> backlinkNames = new ArrayList<String>();
        List<DocumentReference> backlinkReferences = loadBacklinks(
            this.currentMixedDocumentReferenceResolver.resolve(fullName), bTransaction, context);
        for (DocumentReference backlinkReference : backlinkReferences) {
            backlinkNames.add(this.localEntityReferenceSerializer.serialize(backlinkReference));
        }
        return backlinkNames;
    }

    public void saveLinks(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // need to delete existing links before saving the page's one
            deleteLinks(doc.getId(), context, bTransaction);

            // necessary to blank links from doc
            context.remove("links");

            if (doc.getSyntaxId().equals("xwiki/1.0")) {
                saveLinks10(doc, context, session);
            } else {
                // When not in 1.0 content get WikiLinks directly from XDOM
                Set<XWikiLink> links = doc.getUniqueWikiLinkedPages(context);
                for (XWikiLink wikiLink : links) {
                    session.save(wikiLink);
                }
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_LINKS, "Exception while saving links", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private void saveLinks10(XWikiDocument doc, XWikiContext context, Session session) throws XWikiException
    {
        // call to RenderEngine and converting the list of links into a list of backlinks
        // Note: We need to set the passed document as the current document as the "wiki"
        // renderer uses context.getDoc().getSpace() to find out the space name if no
        // space is specified in the link. A better implementation would be to pass
        // explicitely the current space to the render() method.
        ExecutionContext econtext = Utils.getComponent(Execution.class).getContext();

        List<String> links;
        try {
            // Create new clean context to avoid wiki manager plugin requests in same session
            XWikiContext renderContext = (XWikiContext) context.clone();

            renderContext.setDoc(doc);
            econtext.setProperty("xwikicontext", renderContext);

            setSession(null, renderContext);
            setTransaction(null, renderContext);

            XWikiRenderer renderer = renderContext.getWiki().getRenderingEngine().getRenderer("wiki");
            renderer.render(doc.getContent(), doc, doc, renderContext);

            links = (List<String>) renderContext.get("links");
        } catch (Exception e) {
            // If the rendering fails lets forget backlinks without errors
            links = Collections.emptyList();
        } finally {
            econtext.setProperty("xwikicontext", context);
        }

        if (links != null) {
            for (String reference : links) {
                // XWikiLink is the object declared in the Hibernate mapping
                XWikiLink link = new XWikiLink();
                link.setDocId(doc.getId());
                link.setFullName(doc.getFullName());
                link.setLink(reference);

                session.save(link);
            }
        }
    }

    public void deleteLinks(long docId, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("delete from XWikiLink as link where link.id.docId = :docId");
            query.setLong("docId", docId);
            query.executeUpdate();

            if (bTransaction) {
                endTransaction(context, true);
                bTransaction = false;
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_LINKS, "Exception while deleting links", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {
            }
        }
    }

    public void getContent(XWikiDocument doc, StringBuffer buf)
    {
        buf.append(doc.getContent());
    }

    public List<String> getClassList(XWikiContext context) throws XWikiException
    {
        boolean bTransaction = true;
        try {
            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);

            Query query =
                session.createQuery("select doc.fullName from XWikiDocument as doc "
                + "where (doc.xWikiClassXML is not null and doc.xWikiClassXML like '<%')");
            Iterator<String> it = query.list().iterator();
            List<String> list = new ArrayList<String>();
            while (it.hasNext()) {
                String name = it.next();
                list.add(name);
            }

            if (useClassesTable(false, context)) {
                query = session.createQuery("select bclass.name from BaseClass as bclass");
                it = query.list().iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    if (!list.contains(name)) {
                        list.add(name);
                    }
                }
            }
            if (bTransaction) {
                endTransaction(context, false, false);
            }
            return list;
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH, "Exception while searching class list", e);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }
        }
    }

    private boolean useClassesTable(boolean write, XWikiContext context)
    {
        String param = "xwiki.store.hibernate.useclasstables";
        if (write) {
            return ("1".equals(context.getWiki().Param(param + ".write", "0")));
        } else {
            return ("1".equals(context.getWiki().Param(param + ".read", "1")));
        }
    }

    /**
     * Add values into named query.
     * 
     * @param parameterId the parameter id to increment.
     * @param query the query to fill.
     * @param parameterValues the values to add to query.
     * @return the id of the next parameter to add.
     */
    private int injectParameterListToQuery(int parameterId, Query query, Collection parameterValues)
    {
        int index = parameterId;

        if (parameterValues != null) {
            for (Iterator valueIt = parameterValues.iterator(); valueIt.hasNext(); ++index) {
                injectParameterToQuery(index, query, valueIt.next());
            }
        }

        return index;
    }

    /**
     * Add value into named query.
     * 
     * @param parameterId the parameter id to increment.
     * @param query the query to fill.
     * @param parameterValue the values to add to query.
     */
    private void injectParameterToQuery(int parameterId, Query query, Object parameterValue)
    {
        query.setParameter(parameterId, parameterValue);
    }

    /**
     * @since 2.2M2
     */
    public List<DocumentReference> searchDocumentReferences(String parametrizedSqlClause, List< ? > parameterValues,
        XWikiContext context) throws XWikiException
    {
        return searchDocumentReferences(parametrizedSqlClause, 0, 0, parameterValues, context);
    }

    /**
     * @deprecated since 2.2M2 use {@link #searchDocumentReferences(String, List, com.xpn.xwiki.XWikiContext)}
     */
    @Deprecated
    public List<String> searchDocumentsNames(String parametrizedSqlClause, List< ? > parameterValues,
        XWikiContext context)
        throws XWikiException
    {
        return searchDocumentsNames(parametrizedSqlClause, 0, 0, parameterValues, context);
    }

    /**
     * @since 2.2M1
     */
    public List<DocumentReference> searchDocumentReferences(String parametrizedSqlClause, int nb, int start,
        List< ? > parameterValues, XWikiContext context) throws XWikiException
    {
        String sql = createSQLQuery("select distinct doc.space, doc.name", parametrizedSqlClause);
        return searchDocumentReferencesInternal(sql, nb, start, parameterValues, context);
    }

    /**
     * @deprecated since 2.2M1 use {@link #searchDocumentReferences(String, int, int, List, XWikiContext)}
     */
    @Deprecated
    public List<String> searchDocumentsNames(String parametrizedSqlClause, int nb, int start,
        List< ? > parameterValues, XWikiContext context) throws XWikiException
    {
        String sql = createSQLQuery("select distinct doc.space, doc.name", parametrizedSqlClause);
        return searchDocumentsNamesInternal(sql, nb, start, parameterValues, context);
    }

    /**
     * @since 2.2M2
     */
    public List<DocumentReference> searchDocumentReferences(String wheresql, XWikiContext context)
        throws XWikiException
    {
        return searchDocumentReferences(wheresql, 0, 0, "", context);
    }

    /**
     * @deprecated since 2.2M1 use {@link #searchDocumentReferences(String, XWikiContext)}
     */
    @Deprecated
    public List<String> searchDocumentsNames(String wheresql, XWikiContext context) throws XWikiException
    {
        return searchDocumentsNames(wheresql, 0, 0, "", context);
    }

    /**
     * @since 2.2M2
     */
    public List<DocumentReference> searchDocumentReferences(String wheresql, int nb, int start, XWikiContext context)
        throws XWikiException
    {
        return searchDocumentReferences(wheresql, nb, start, "", context);
    }

    /**
     * @deprecated since 2.2M1 use {@link #searchDocumentReferences(String, int, int, XWikiContext)}
     */
    @Deprecated
    public List<String> searchDocumentsNames(String wheresql, int nb, int start, XWikiContext context)
        throws XWikiException
    {
        return searchDocumentsNames(wheresql, nb, start, "", context);
    }

    /**
     * @since 2.2M2
     */
    public List<DocumentReference> searchDocumentReferences(String wheresql, int nb, int start, String selectColumns,
        XWikiContext context) throws XWikiException
    {
        String sql = createSQLQuery("select distinct doc.space, doc.name", wheresql);
        return searchDocumentReferencesInternal(sql, nb, start, Collections.EMPTY_LIST, context);
    }

    /**
     * @deprecated since 2.2M1 use {@link #searchDocumentReferences(String, int, int, String, XWikiContext)}
     */
    @Deprecated
    public List<String> searchDocumentsNames(String wheresql, int nb, int start, String selectColumns,
        XWikiContext context) throws XWikiException
    {
        String sql = createSQLQuery("select distinct doc.space, doc.name", wheresql);
        return searchDocumentsNamesInternal(sql, nb, start, Collections.EMPTY_LIST, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#search(java.lang.String, int, int, com.xpn.xwiki.XWikiContext)
     */
    public <T> List<T> search(String sql, int nb, int start, XWikiContext context) throws XWikiException
    {
        return search(sql, nb, start, (List) null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#search(java.lang.String, int, int, java.util.List,
     *      com.xpn.xwiki.XWikiContext)
     */
    public <T> List<T> search(String sql, int nb, int start, List< ? > parameterValues, XWikiContext context)
        throws XWikiException
    {
        return search(sql, nb, start, null, parameterValues, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#search(java.lang.String, int, int, java.lang.Object[][],
     *      com.xpn.xwiki.XWikiContext)
     */
    public <T> List<T> search(String sql, int nb, int start, Object[][] whereParams, XWikiContext context)
        throws XWikiException
    {
        return search(sql, nb, start, whereParams, null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#search(java.lang.String, int, int, java.lang.Object[][],
     *      java.util.List, com.xpn.xwiki.XWikiContext)
     */
    public <T> List<T> search(String sql, int nb, int start, Object[][] whereParams, List< ? > parameterValues,
        XWikiContext context)
        throws XWikiException
    {
        boolean bTransaction = true;

        if (sql == null) {
            return null;
        }

        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate");
            }
            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);

            if (whereParams != null) {
                sql += generateWhereStatement(whereParams);
            }

            Query query = session.createQuery(filterSQL(sql));

            // Add values for provided HQL request containing "?" characters where to insert real
            // values.
            int parameterId = injectParameterListToQuery(0, query, parameterValues);

            if (whereParams != null) {
                for (int i = 0; i < whereParams.length; i++) {
                    query.setString(parameterId++, (String) whereParams[i][1]);
                }
            }

            if (start != 0) {
                query.setFirstResult(start);
            }
            if (nb != 0) {
                query.setMaxResults(nb);
            }
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                list.add(it.next());
            }
            return list;
        } catch (Exception e) {
            Object[] args = {sql};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH, "Exception while searching documents with sql {0}",
                e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }
    }

    private String generateWhereStatement(Object[][] whereParams)
    {
        StringBuffer str = new StringBuffer();

        str.append(" where ");
        for (int i = 0; i < whereParams.length; i++) {
            if (i > 0) {
                if (whereParams[i - 1].length >= 4 && whereParams[i - 1][3] != "" && whereParams[i - 1][3] != null) {
                    str.append(" ");
                    str.append(whereParams[i - 1][3]);
                    str.append(" ");
                } else {
                    str.append(" and ");
                }
            }
            str.append(whereParams[i][0]);
            if (whereParams[i].length >= 3 && whereParams[i][2] != "" && whereParams[i][2] != null) {
                str.append(" ");
                str.append(whereParams[i][2]);
                str.append(" ");
            } else {
                str.append(" = ");
            }
            str.append(" ?");
        }
        return str.toString();
    }

    public List search(Query query, int nb, int start, XWikiContext context) throws XWikiException
    {
        boolean bTransaction = true;

        if (query == null) {
            return null;
        }

        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate", query.getQueryString());
            }
            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            if (start != 0) {
                query.setFirstResult(start);
            }
            if (nb != 0) {
                query.setMaxResults(nb);
            }
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                list.add(it.next());
            }
            if (bTransaction) {
                // The session is closed here, too.
                endTransaction(context, false, false);
                bTransaction = false;
            }
            return list;
        } catch (Exception e) {
            Object[] args = {query.toString()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH, "Exception while searching documents with sql {0}",
                e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see XWikiStoreInterface#countDocuments(String, XWikiContext)
     */
    public int countDocuments(String wheresql, XWikiContext context) throws XWikiException
    {
        String sql = createSQLQuery("select count(distinct doc.fullName)", wheresql);
        List l = search(sql, 0, 0, context);
        return ((Number) l.get(0)).intValue();
    }

    /**
     * {@inheritDoc}
     * 
     * @see XWikiStoreInterface#countDocuments(String, List, XWikiContext)
     */
    public int countDocuments(String parametrizedSqlClause, List< ? > parameterValues, XWikiContext context)
        throws XWikiException
    {
        String sql = createSQLQuery("select count(distinct doc.fullName)", parametrizedSqlClause);
        List l = search(sql, 0, 0, parameterValues, context);
        return ((Number) l.get(0)).intValue();
    }

    /**
     * @deprecated since 2.2M1 used {@link #searchDocumentReferencesInternal(String, int, int, List, XWikiContext)}
     */
    @Deprecated
    private List<String> searchDocumentsNamesInternal(String sql, int nb, int start, List parameterValues,
        XWikiContext context) throws XWikiException
    {
        List<String> documentNames = new ArrayList<String>();
        for (DocumentReference reference : searchDocumentReferencesInternal(sql, nb, start, parameterValues, context)) {
            documentNames.add(this.compactWikiEntityReferenceSerializer.serialize(reference));
        }
        return documentNames;
    }

    /**
     * @since 2.2M1
     */
    private List<DocumentReference> searchDocumentReferencesInternal(String sql, int nb, int start,
        List parameterValues, XWikiContext context) throws XWikiException
    {
        List<DocumentReference> documentReferences = new ArrayList<DocumentReference>();
        for (Object[] result : searchGenericInternal(sql, nb, start, parameterValues, context)) {
            // Construct a reference, using the current wiki as the wiki reference name. This is because the wiki
            // name is not stored in the database for document references.
            DocumentReference reference = new DocumentReference((String) result[1],
                new SpaceReference((String) result[0], new WikiReference(context.getDatabase())));
            documentReferences.add(reference);
        }
        return documentReferences;
    }

    /**
     * @since 2.2M1
     */
    private List<Object[]> searchGenericInternal(String sql, int nb, int start,
        List parameterValues, XWikiContext context) throws XWikiException
    {
        boolean bTransaction = false;
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate", sql);
            }

            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);
            Query query = session.createQuery(filterSQL(sql));

            injectParameterListToQuery(0, query, parameterValues);

            if (start != 0) {
                query.setFirstResult(start);
            }
            if (nb != 0) {
                query.setMaxResults(nb);
            }
            Iterator it = query.list().iterator();
            List<Object[]> list = new ArrayList<Object[]>();
            while (it.hasNext()) {
                list.add((Object[]) it.next());
            }
            return list;
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                "Exception while searching documents with SQL [{0}]", e, new Object[] {sql});
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, boolean, boolean, int,
     *      int, com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping,
        boolean checkRight, int nb, int start, XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, customMapping, checkRight, nb, start, null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, boolean, boolean, int,
     *      int, java.util.List, com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping,
        boolean checkRight, int nb, int start, List< ? > parameterValues, XWikiContext context) throws XWikiException
    {
        // Search documents
        List<Object[]> documentDatas = new ArrayList<Object[]>();
        boolean bTransaction = true;
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            String sql;
            if (distinctbylanguage) {
                sql = createSQLQuery("select distinct doc.space, doc.name, doc.language", wheresql);
            } else {
                sql = createSQLQuery("select distinct doc.space, doc.name", wheresql);
            }

            // Start monitoring timer
            if (monitor != null) {
                monitor.startTimer("hibernate", sql);
            }

            checkHibernate(context);
            if (bTransaction) {
                // Inject everything until we know what's needed
                SessionFactory sfactory =
                    customMapping ? injectCustomMappingsInSessionFactory(context) : getSessionFactory();
                bTransaction = beginTransaction(sfactory, false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery(filterSQL(sql));

            injectParameterListToQuery(0, query, parameterValues);

            if (start != 0) {
                query.setFirstResult(start);
            }
            if (nb != 0) {
                query.setMaxResults(nb);
            }
            documentDatas.addAll(query.list());
            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                "Exception while searching documents with SQL [{0}]", e, new Object[] {wheresql});
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false, false);
                }
            } catch (Exception e) {
            }

            // End monitoring timer
            if (monitor != null) {
                monitor.endTimer("hibernate");
            }
        }

        // Resolve documents. We use two separated sessions because rights service could need to switch database to
        // check rights
        List<XWikiDocument> documents = new ArrayList<XWikiDocument>();
        for (Object[] result : documentDatas) {
            XWikiDocument doc = new XWikiDocument(new DocumentReference(context.getDatabase(), (String) result[0], (String) result[1]));
            if (checkRight) {
                if (context.getWiki().getRightService().checkAccess("view", doc, context) == false) {
                    continue;
                }
            }

            DocumentReference documentReference = doc.getDocumentReference();
            if (distinctbylanguage) {
                String language = (String) result[2];
                XWikiDocument document = context.getWiki().getDocument(documentReference, context);
                if ((language == null) || (language.equals(""))) {
                    documents.add(document);
                } else {
                    documents.add(document.getTranslatedDocument(language, context));
                }
            } else {
                documents.add(context.getWiki().getDocument(documentReference, context));
            }
        }

        return documents;
    }

    /**
     * @param queryPrefix the start of the SQL query (for example "select distinct doc.space, doc.name")
     * @param whereSQL the where clause to append
     * @return the full formed SQL query, to which the order by columns have been added as returned columns (this is
     *         required for example for HSQLDB).
     */
    protected String createSQLQuery(String queryPrefix, String whereSQL)
    {
        StringBuffer sql = new StringBuffer(queryPrefix);

        String normalizedWhereSQL;
        if (StringUtils.isBlank(whereSQL)) {
            normalizedWhereSQL = "";
        } else {
            normalizedWhereSQL = whereSQL.trim();
        }

        sql.append(getColumnsForSelectStatement(normalizedWhereSQL));
        sql.append(" from XWikiDocument as doc");

        if (!normalizedWhereSQL.equals("")) {
            if ((!normalizedWhereSQL.startsWith("where")) && (!normalizedWhereSQL.startsWith(","))) {
                sql.append(" where ");
            } else {
                sql.append(" ");
            }
            sql.append(normalizedWhereSQL);
        }

        String result = sql.toString();
        int idx = result.toLowerCase().indexOf("where ");
        if (idx >= 0) {
            // With 'WHERE'
            idx = idx + 6;
            result =
                result.substring(0, idx) + "(doc.hidden <> true or doc.hidden is null) and " + result.substring(idx);
        } else {
            // Without 'WHERE'
            int oidx = Math.min(result.toLowerCase().indexOf("order by "), Integer.MAX_VALUE);
            int gidx = Math.min(result.toLowerCase().indexOf("group by "), Integer.MAX_VALUE);
            idx = Math.min(oidx, gidx);
            if (idx > 0 && idx < Integer.MAX_VALUE) {
                // Without 'WHERE', but with 'ORDER BY' or 'GROUP BY'
                result =
                    result.substring(0, idx) + "where doc.hidden <> true or doc.hidden is null "
                    + result.substring(idx);
            } else {
                // Without 'WHERE', 'ORDER BY' or 'GROUP BY'... This should not happen at all.
                result = result + " where (doc.hidden <> true or doc.hidden is null)";
            }
            // TODO: Take into account GROUP BY, HAVING and other keywords when there's no WHERE in the query
        }

        return result;
    }

    /**
     * @param whereSQL the SQL where clause
     * @return the list of columns to return in the select clause as a string starting with ", " if there are columns or
     *         an empty string otherwise. The returned columns are extracted from the where clause. One reason for doing
     *         so is because HSQLDB only support SELECT DISTINCT SQL statements where the columns operated on are
     *         returned from the query.
     */
    protected String getColumnsForSelectStatement(String whereSQL)
    {
        StringBuffer columns = new StringBuffer();

        int orderByPos = whereSQL.toLowerCase().indexOf("order by");
        if (orderByPos >= 0) {
            String orderByStatement = whereSQL.substring(orderByPos + "order by".length() + 1);
            StringTokenizer tokenizer = new StringTokenizer(orderByStatement, ",");
            while (tokenizer.hasMoreTokens()) {
                String column = tokenizer.nextToken().trim();
                // Remove "desc" or "asc" from the column found
                column = StringUtils.removeEndIgnoreCase(column, " desc");
                column = StringUtils.removeEndIgnoreCase(column, " asc");
                columns.append(", ").append(column.trim());
            }
        }

        return columns.toString();
    }

    public boolean isCustomMappingValid(BaseClass bclass, String custommapping1, XWikiContext context)
    {
        try {
            Configuration hibconfig = makeMapping(bclass.getName(), custommapping1);
            return isValidCustomMapping(bclass.getName(), hibconfig, bclass);
        } catch (Exception e) {
            return false;
        }
    }

    private SessionFactory injectCustomMappingsInSessionFactory(XWikiDocument doc, XWikiContext context)
        throws XWikiException
    {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings() == false) {
            return getSessionFactory();
        }

        boolean result = injectCustomMappings(doc, context);
        if (result == false) {
            return getSessionFactory();
        }

        Configuration config = getConfiguration();
        SessionFactoryImpl sfactory = (SessionFactoryImpl) config.buildSessionFactory();
        Settings settings = sfactory.getSettings();
        ConnectionProvider provider = ((SessionFactoryImpl) getSessionFactory()).getSettings().getConnectionProvider();
        Field field = null;
        try {
            field = settings.getClass().getDeclaredField("connectionProvider");
            field.setAccessible(true);
            field.set(settings, provider);
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_MAPPING_INJECTION_FAILED, "Mapping injection failed", e);
        }
        return sfactory;
    }

    public void injectCustomMappings(XWikiContext context) throws XWikiException
    {
        SessionFactory sfactory = injectCustomMappingsInSessionFactory(context);
        setSessionFactory(sfactory);
    }

    public void injectUpdatedCustomMappings(XWikiContext context) throws XWikiException
    {
        Configuration config = getConfiguration();
        setSessionFactory(injectInSessionFactory(config));
    }

    public SessionFactory injectCustomMappingsInSessionFactory(BaseClass bclass, XWikiContext context)
        throws XWikiException
    {
        boolean result = injectCustomMapping(bclass, context);
        if (result == false) {
            return getSessionFactory();
        }

        Configuration config = getConfiguration();
        return injectInSessionFactory(config);
    }

    private SessionFactory injectInSessionFactory(Configuration config) throws XWikiException
    {
        SessionFactoryImpl sfactory = (SessionFactoryImpl) config.buildSessionFactory();
        Settings settings = sfactory.getSettings();
        ConnectionProvider provider = ((SessionFactoryImpl) getSessionFactory()).getSettings().getConnectionProvider();
        Field field = null;
        try {
            field = settings.getClass().getDeclaredField("connectionProvider");
            field.setAccessible(true);
            field.set(settings, provider);
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_MAPPING_INJECTION_FAILED, "Mapping injection failed", e);
        }
        return sfactory;
    }

    public SessionFactory injectCustomMappingsInSessionFactory(XWikiContext context) throws XWikiException
    {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings() == false) {
            return getSessionFactory();
        }

        List list;
        if (useClassesTable(true, context)) {
            list =
                searchDocuments(
                    ", BaseClass as bclass where bclass.name=doc.fullName and bclass.customMapping is not null", true,
                    false, false, 0, 0, context);
        }
        list = searchDocuments("", true, false, false, 0, 0, context);
        boolean result = false;

        for (int i = 0; i < list.size(); i++) {
            XWikiDocument doc = (XWikiDocument) list.get(i);
            if (doc.getXClass().getFieldList().size() > 0) {
                result |= injectCustomMapping(doc.getXClass(), context);
            }
        }

        if (result == false) {
            return getSessionFactory();
        }

        Configuration config = getConfiguration();
        return injectInSessionFactory(config);
    }

    public boolean injectCustomMappings(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings() == false) {
            return false;
        }

        boolean result = false;
        for (List<BaseObject> objectsOfType : doc.getXObjects().values()) {
            for (BaseObject object : objectsOfType) {
                if (object != null) {
                    result |= injectCustomMapping(object.getXClass(context), context);
                    // Each class must be mapped only once
                    break;
                }
            }
        }
        return result;
    }

    public boolean injectCustomMapping(BaseClass doc1class, XWikiContext context) throws XWikiException
    {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings() == false) {
            return false;
        }

        String custommapping = doc1class.getCustomMapping();
        if (!doc1class.hasExternalCustomMapping()) {
            return false;
        }

        Configuration config = getConfiguration();

        // don't add a mapping that's already there
        if (config.getClassMapping(doc1class.getName()) != null) {
            return true;
        }

        Configuration mapconfig = makeMapping(doc1class.getName(), custommapping);
        if (!isValidCustomMapping(doc1class.getName(), mapconfig, doc1class)) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE,
                XWikiException.ERROR_XWIKI_STORE_HIBERNATE_INVALID_MAPPING, "Invalid Custom Mapping");
        }

        config.addXML(makeMapping(doc1class.getName(), "xwikicustom_" + doc1class.getName().replaceAll("\\.", "_"),
            custommapping));
        config.buildMappings();
        return true;
    }

    private boolean isValidCustomMapping(String className, Configuration hibconfig, BaseClass bclass)
    {
        PersistentClass mapping = hibconfig.getClassMapping(className);
        if (mapping == null) {
            return true;
        }

        Iterator it = mapping.getPropertyIterator();
        while (it.hasNext()) {
            Property hibprop = (Property) it.next();
            String propname = hibprop.getName();
            PropertyClass propclass = (PropertyClass) bclass.getField(propname);
            if (propclass == null) {
                log.warn("Mapping contains invalid field name " + propname);
                return false;
            }

            boolean result = isValidColumnType(hibprop.getValue().getType().getName(), propclass.getClassName());
            if (result == false) {
                log.warn("Mapping contains invalid type in field " + propname);
                return false;
            }
        }

        return true;
    }

    public List getCustomMappingPropertyList(BaseClass bclass)
    {
        List list = new ArrayList();
        Configuration hibconfig;
        if (bclass.hasExternalCustomMapping()) {
            hibconfig = makeMapping(bclass.getName(), bclass.getCustomMapping());
        } else {
            hibconfig = getConfiguration();
        }
        PersistentClass mapping = hibconfig.getClassMapping(bclass.getName());
        if (mapping == null) {
            return null;
        }

        Iterator it = mapping.getPropertyIterator();
        while (it.hasNext()) {
            Property hibprop = (Property) it.next();
            String propname = hibprop.getName();
            list.add(propname);
        }
        return list;
    }

    private boolean isValidColumnType(String name, String className)
    {
        String[] validtypes = this.validTypesMap.get(className);
        if (validtypes == null) {
            return true;
        } else {
            return ArrayUtils.contains(validtypes, name);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, java.util.List,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, List< ? > parameterValues, XWikiContext context)
        throws XWikiException
    {
        return searchDocuments(wheresql, 0, 0, parameterValues, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, XWikiContext context)
        throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, 0, 0, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, boolean,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping,
        XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, customMapping, 0, 0, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, int, int,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, int nb, int start, XWikiContext context)
        throws XWikiException
    {
        return searchDocuments(wheresql, nb, start, null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, int, int, java.util.List,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, int nb, int start, List< ? > parameterValues,
        XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, true, nb, start, parameterValues, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, int, int, java.util.List,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, int nb, int start,
        List< ? > parameterValues, XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, false, nb, start, parameterValues, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, int, int,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, int nb, int start,
        XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, nb, start, null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, boolean, int, int,
     *      com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping,
        int nb, int start, XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, customMapping, nb, start, null, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.store.XWikiStoreInterface#searchDocuments(java.lang.String, boolean, boolean, int, int,
     *      java.util.List, com.xpn.xwiki.XWikiContext)
     */
    public List<XWikiDocument> searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping,
        int nb, int start, List< ? > parameterValues, XWikiContext context) throws XWikiException
    {
        return searchDocuments(wheresql, distinctbylanguage, customMapping, true, nb, start, parameterValues, context);
    }

    public List<String> getTranslationList(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        String hql = "select doc.language from XWikiDocument as doc where doc.space = ? and doc.name = ? "
            + "and (doc.language <> '' or (doc.language is not null and '' is null))";
        ArrayList<String> params = new ArrayList<String>();
        params.add(doc.getSpace());
        params.add(doc.getName());
        List<String> list = search(hql, 0, 0, params, context);
        return (list == null) ? new ArrayList<String>() : list;
    }

    /**
     * {@inheritDoc}
     */
    public QueryManager getQueryManager()
    {
        return this.queryManager;
    }

    /**
     * This is in response to the fact that Hibernate interprets backslashes differently from the database.
     * Our solution is to simply replace all instances of \ with \\ which makes the first backslash escape the second.
     *
     * @param sql the uncleaned sql.
     * @return same as sql except it is guarenteed not to contain groups of odd numbers of backslashes.
     * @since 2.4M1
     */
    private String filterSQL(String sql)
    {
        return StringUtils.replace(sql, "\\", "\\\\");
    }
}
