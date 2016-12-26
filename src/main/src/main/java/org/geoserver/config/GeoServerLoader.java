/* (c) 2014 - 2015 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.config;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogFactory;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ResourcePool;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WMSLayerInfo;
import org.geoserver.catalog.WMSStoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.Wrapper;
import org.geoserver.catalog.event.CatalogListener;
import org.geoserver.catalog.impl.CatalogImpl;
import org.geoserver.catalog.util.LegacyCatalogImporter;
import org.geoserver.catalog.util.LegacyCatalogReader;
import org.geoserver.catalog.util.LegacyFeatureTypeInfoReader;
import org.geoserver.config.util.LegacyConfigurationImporter;
import org.geoserver.config.util.XStreamPersister;
import org.geoserver.config.util.XStreamPersisterFactory;
import org.geoserver.config.util.XStreamServiceLoader;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.GeoServerResourceLoader;
import org.geoserver.platform.resource.Paths;
import org.geoserver.platform.resource.Resource;
import org.geoserver.platform.resource.Resource.Type;
import org.geoserver.platform.resource.Resources;
import org.geoserver.util.IOUtils;
import org.geotools.util.logging.Logging;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;

/**
 * Initializes GeoServer configuration and catalog on startup.
 * <p>
 * This class post processes the singleton beans {@link Catalog} and {@link GeoServer}, populating 
 * them from stored configuration. 
 * </p>
 * @author Justin Deoliveira, The Open Planning Project
 *
 */
public abstract class GeoServerLoader {

    static Logger LOGGER = Logging.getLogger( "org.geoserver" );
    
    protected GeoServerResourceLoader resourceLoader;
    GeoServer geoserver;
    XStreamPersisterFactory xpf = new XStreamPersisterFactory();
    
    //JD: this is a hack for the moment, it is used only to maintain tests since the test setup relies
    // on the old data directory structure, once the tests have been ported to the new structure
    // this ugly hack can die
    static boolean legacy = false;
    
    public GeoServerLoader( GeoServerResourceLoader resourceLoader ) {
        this.resourceLoader = resourceLoader;
    }
    
    public void setApplicationContext(ApplicationContext applicationContext)
            throws BeansException {
    }
    
    public void setXStreamPeristerFactory(XStreamPersisterFactory xpf) {
        this.xpf = xpf;
    }
    
    public static void setLegacy(boolean legacy) {
        GeoServerLoader.legacy = legacy;
    }
    
    public final Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        return bean;
    }

    public final Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        if ( bean instanceof Catalog ) {
            //ensure this is not a wrapper but the real deal
            if ( bean instanceof Wrapper && ((Wrapper) bean).isWrapperFor(Catalog.class) ) {
                return bean;
            }
            
            //load
            try {
                Catalog catalog = (Catalog) bean;
                XStreamPersister xp = xpf.createXMLPersister();
                xp.setCatalog( catalog );
                loadCatalog( catalog, xp );
                
                //initialize styles
                initializeStyles(catalog, xp);
            } 
            catch (Exception e) {
                throw new RuntimeException( e );
            }
        }
        
        if ( bean instanceof GeoServer ) {
            geoserver = (GeoServer) bean;
            try {
                XStreamPersister xp = xpf.createXMLPersister() ;
                xp.setCatalog(geoserver.getCatalog());
                loadGeoServer(geoserver, xp);
                
                //load initializers
                loadInitializers(geoserver);
            } 
            catch (Exception e) {
                throw new RuntimeException( e );
            }
            //initialize();
        }
        
        return bean;
    }

    protected abstract void loadCatalog(Catalog catalog, XStreamPersister xp) throws Exception;

    protected abstract void loadGeoServer(final GeoServer geoServer, XStreamPersister xp) throws Exception;

    protected void loadInitializers(GeoServer geoServer) throws Exception {
        //load initializer extensions
        List<GeoServerInitializer> initializers = GeoServerExtensions.extensions( GeoServerInitializer.class );
        for ( GeoServerInitializer initer : initializers ) {
            try {
                initer.initialize( geoServer );
            }
            catch( Throwable t ) {
                LOGGER.log(Level.SEVERE, "Failed to run initializer " + initer, t);
            }
        }
    }
    
    protected void reloadInitializers(GeoServer geoServer) throws Exception {
        //reload applicable initializer extensions
        List<GeoServerReinitializer> initializers = GeoServerExtensions.extensions( GeoServerReinitializer.class );
        for ( GeoServerReinitializer initer : initializers ) {
            try {
                initer.reinitialize( geoServer );
            }
            catch( Throwable t ) {
                LOGGER.log(Level.SEVERE, "Failed to run initializer " + initer, t);
            }
        }
    }
    
    /**
     * Does some post processing on the catalog to ensure that the "well-known" styles
     * are always around.
     */
    protected void initializeStyles( Catalog catalog, XStreamPersister xp) throws IOException {
        if ( catalog.getStyleByName( StyleInfo.DEFAULT_POINT ) == null ) {
            initializeStyle( catalog, StyleInfo.DEFAULT_POINT, "default_point.sld" );
        }
        if ( catalog.getStyleByName( StyleInfo.DEFAULT_LINE ) == null ) {
            initializeStyle( catalog, StyleInfo.DEFAULT_LINE, "default_line.sld" );
        }
        if ( catalog.getStyleByName( StyleInfo.DEFAULT_POLYGON ) == null ) {    
            initializeStyle( catalog, StyleInfo.DEFAULT_POLYGON, "default_polygon.sld" );
        }
        if ( catalog.getStyleByName( StyleInfo.DEFAULT_RASTER ) == null ) {
            initializeStyle( catalog, StyleInfo.DEFAULT_RASTER, "default_raster.sld" );
        }
        if (catalog.getStyleByName(StyleInfo.DEFAULT_GENERIC) == null) {
            initializeStyle(catalog, StyleInfo.DEFAULT_GENERIC, "default_generic.sld");
        }
    }
    
    /**
     * Copies a well known style out to the data directory and adds a catalog entry for it.
     */
    void initializeStyle(Catalog catalog, String styleName, String sld) throws IOException {

        // copy the file out to the data directory if necessary
        Resource styleResource = resourceLoader.get(Paths.path("styles", sld));
        if (!Resources.exists(styleResource)) {
            IOUtils.copy(GeoServerLoader.class.getResourceAsStream(sld), styleResource.out());
        }

        // create a style for it
        StyleInfo s = catalog.getFactory().createStyle();
        s.setName(styleName);
        s.setFilename(sld);
        catalog.add(s);
    }
    
    public void reload() throws Exception {
        destroy();
        
        //reload catalog, make sure we reload the underlying catalog, not any wrappers
        Catalog catalog = geoserver.getCatalog();
        if ( catalog instanceof Wrapper ) {
            catalog = ((Wrapper)geoserver.getCatalog()).unwrap(Catalog.class);
        }
        
        XStreamPersister xp = xpf.createXMLPersister();
        xp.setCatalog( catalog );
        
        loadCatalog( catalog, xp );
        loadGeoServer( geoserver, xp);
        
        reloadInitializers(geoserver);
    }

    protected void readCatalog(Catalog catalog, XStreamPersister xp) throws Exception {
        // we are going to synch up the catalogs and need to preserve listeners,
        // but these two fellas are attached to the new catalog as well
        catalog.removeListeners(ResourcePool.CacheClearingListener.class);
        catalog.removeListeners(GeoServerPersister.class);
        List<CatalogListener> listeners = new ArrayList<CatalogListener>(catalog.getListeners());

        //look for catalog.xml, if it exists assume we are dealing with 
        // an old data directory
        Resource f = resourceLoader.get( "catalog.xml" );
        if ( !Resources.exists(f) ) {
            //assume 2.x style data directory
            CatalogImpl catalog2 = (CatalogImpl) readCatalog( xp );
            // make to remove the old resource pool catalog listener
            ((CatalogImpl)catalog).sync( catalog2 );
        } else {
            // import old style catalog, register the persister now so that we start 
            // with a new version of the catalog
            CatalogImpl catalog2 = (CatalogImpl) readLegacyCatalog( f, xp );
            ((CatalogImpl)catalog).sync( catalog2 );
        }
        
        // attach back the old listeners
        for (CatalogListener listener : listeners) {
            catalog.addListener(listener);
        }
    }
    
    /**
     * Reads the catalog from disk.
     */
    Catalog readCatalog( XStreamPersister xp ) throws Exception {
        CatalogImpl catalog = new CatalogImpl();
        catalog.setResourceLoader(resourceLoader);
        xp.setCatalog( catalog );
        xp.setUnwrapNulls(false);
        
        CatalogFactory factory = catalog.getFactory();
       
        //global styles
        loadStyles(resourceLoader.get( "styles" ), catalog, xp);

        //workspaces, stores, and resources
        Resource workspaces = resourceLoader.get( "workspaces" );
        if ( Resources.exists(workspaces) ) {
            //do a first quick scan over all workspaces, setting the default
            Resource dws = workspaces.get("default.xml");
            WorkspaceInfo defaultWorkspace = null;
            if (Resources.exists(dws)) {
                try {
                    defaultWorkspace = depersist(xp, dws, WorkspaceInfo.class);
                    LOGGER.info("Loaded default workspace " + defaultWorkspace.getName());
                }
                catch( Exception e ) {
                    LOGGER.log(Level.WARNING, "Failed to load default workspace", e);
                }
            }
            else {
                LOGGER.warning("No default workspace was found.");
            }
            
            for ( Resource wsd : Resources.list(workspaces, Resources.DirectoryFilter.INSTANCE) ) {
                Resource f = wsd.get("workspace.xml");
                if ( !Resources.exists(f) ) {
                    continue;
                }
                
                WorkspaceInfo ws = null;
                try {
                    ws = depersist( xp, f, WorkspaceInfo.class );
                    catalog.add( ws );    
                }
                catch( Exception e ) {
                    LOGGER.log( Level.WARNING, "Failed to load workspace '" + wsd.name() + "'" , e );
                    continue;
                }
                
                LOGGER.info( "Loaded workspace '" + ws.getName() +"'");
                
                //load the namespace
                Resource nsf = wsd.get("namespace.xml" );
                NamespaceInfo ns = null; 
                if ( Resources.exists(nsf) ) {
                    try {
                        ns = depersist( xp, nsf, NamespaceInfo.class );
                        catalog.add( ns );
                    }
                    catch( Exception e ) {
                        LOGGER.log( Level.WARNING, "Failed to load namespace for '" + wsd.name() + "'" , e );
                    }
                }
                
                //set the default workspace, this value might be null in the case of coming from a 
                // 2.0.0 data directory. See https://osgeo-org.atlassian.net/browse/GEOS-3440
                if (defaultWorkspace != null ) {
                    if (ws.getName().equals(defaultWorkspace.getName())) {
                        catalog.setDefaultWorkspace(ws);
                        if (ns != null) {
                            catalog.setDefaultNamespace(ns);
                        }
                    }
                }
                else {
                    //create the default.xml file
                    defaultWorkspace = catalog.getDefaultWorkspace();
                    if (defaultWorkspace != null) {
                        try {
                            persist(xp, defaultWorkspace, dws);    
                        }
                        catch( Exception e ) {
                            LOGGER.log( Level.WARNING, "Failed to persist default workspace '" + 
                                wsd.name() + "'" , e );
                        }
                        
                    }
                }

                //load the styles for the workspace
                Resource styles = wsd.get("styles");
                if (styles != null) {
                    loadStyles(styles, catalog, xp);
                }
            }
            
            for ( Resource wsd : Resources.list(workspaces, Resources.DirectoryFilter.INSTANCE )) {
            
                //load the stores for this workspace
                for ( Resource sd : Resources.list(wsd, Resources.DirectoryFilter.INSTANCE) ) {
                    Resource f = sd.get("datastore.xml");
                    if ( Resources.exists(f)) {
                        //load as a datastore
                        DataStoreInfo ds = null;
                        try {    
                            ds = depersist( xp, f, DataStoreInfo.class );
                            catalog.add( ds );
                            
                            LOGGER.info( "Loaded data store '" + ds.getName() +"'");
                            
                            if (ds.isEnabled()) {
                                //connect to the datastore to determine if we should disable it
                                try {
                                    ds.getDataStore(null);
                                }
                                catch( Throwable t ) {
                                    LOGGER.warning( "Error connecting to '" + ds.getName() + "'. Disabling." );
                                    LOGGER.log( Level.INFO, "", t );
                                    
                                    ds.setError(t);
                                    ds.setEnabled(false);
                                }
                            }
                        }
                        catch( Exception e ) {
                            LOGGER.log( Level.WARNING, "Failed to load data store '" + sd.name() + "'", e);
                            continue;
                        }
                        
                        //load feature types
                        for ( Resource ftd : Resources.list(sd, Resources.DirectoryFilter.INSTANCE) ) {
                            f =  ftd.get("featuretype.xml" );
                            if( Resources.exists(f) ) {
                                FeatureTypeInfo ft = null;
                                try {
                                    ft = depersist(xp,f,FeatureTypeInfo.class);
                                    catalog.add(ft);
                                }
                                catch( Exception e ) {
                                    LOGGER.log( Level.WARNING, "Failed to load feature type '" + ftd.name() +"'", e);
                                    continue;
                                }
                                
                                LOGGER.info( "Loaded feature type '" + ds.getName() +"'");
                                
                                f = ftd.get("layer.xml" );
                                if ( Resources.exists(f) ) {
                                    try {
                                        LayerInfo l = depersist(xp, f, LayerInfo.class );
                                        catalog.add( l );
                                        
                                        LOGGER.info( "Loaded layer '" + l.getName() + "'" );
                                    }
                                    catch( Exception e ) {
                                        LOGGER.log( Level.WARNING, "Failed to load layer for feature type '" + ft.getName() +"'", e);
                                    }
                                }
                            }
                            else {
                                LOGGER.warning( "Ignoring feature type directory " + ftd.path() );
                            }
                        }
                    } else {
                        //look for a coverage store
                        f = sd.get("coveragestore.xml" );
                        if ( Resources.exists(f) ) {
                            CoverageStoreInfo cs = null;
                            try {
                                cs = depersist( xp, f, CoverageStoreInfo.class );
                                catalog.add( cs );
                            
                                LOGGER.info( "Loaded coverage store '" + cs.getName() +"'");
                            }
                            catch( Exception e ) {
                                LOGGER.log( Level.WARNING, "Failed to load coverage store '" + sd.name() +"'", e);
                                continue;
                            }
                            
                            //load coverages
                            for ( Resource cd : Resources.list(sd, Resources.DirectoryFilter.INSTANCE) ) {
                                f = cd.get("coverage.xml" );
                                if( Resources.exists(f) ) {
                                    CoverageInfo c = null;
                                    try {
                                        c = depersist(xp,f,CoverageInfo.class);
                                        catalog.add( c );
                                        
                                        LOGGER.info( "Loaded coverage '" + cs.getName() +"'");
                                    }
                                    catch( Exception e ) {
                                        LOGGER.log( Level.WARNING, "Failed to load coverage '" + cd.name() +"'", e);
                                        continue;
                                    }
                                    
                                    f = cd.get("layer.xml" );
                                    if ( Resources.exists(f) ) {
                                        try {
                                            LayerInfo l = depersist(xp, f, LayerInfo.class );
                                            catalog.add( l );
                                            
                                            LOGGER.info( "Loaded layer '" + l.getName() + "'" );
                                        }
                                        catch( Exception e ) {
                                            LOGGER.log( Level.WARNING, "Failed to load layer coverage '" + c.getName() +"'", e);
                                        }
                                    }
                                }
                                else {
                                    LOGGER.warning( "Ignoring coverage directory " + cd.path() );
                                }
                            }
                        } else {
                            f = sd.get("wmsstore.xml");
                            if(Resources.exists(f)) {
                                WMSStoreInfo wms = null;
                                try {
                                    wms = depersist( xp, f, WMSStoreInfo.class );
                                    catalog.add( wms );
                                
                                    LOGGER.info( "Loaded wmsstore '" + wms.getName() +"'");
                                } catch( Exception e ) {
                                    LOGGER.log( Level.WARNING, "Failed to load wms store '" + sd.name() +"'", e);
                                    continue;
                                }
                                
                                //load wms layers
                                for ( Resource cd : Resources.list(sd,Resources.DirectoryFilter.INSTANCE) ) {
                                    f =  cd.get("wmslayer.xml" );
                                    if( Resources.exists(f) ) {
                                        WMSLayerInfo wl = null;
                                        try {
                                            wl = depersist(xp,f,WMSLayerInfo.class);
                                            catalog.add( wl );
                                            
                                            LOGGER.info( "Loaded wms layer'" + wl.getName() +"'");
                                        }
                                        catch( Exception e ) {
                                            LOGGER.log( Level.WARNING, "Failed to load wms layer '" + cd.name() +"'", e);
                                            continue;
                                        }
                                        
                                        f =  cd.get("layer.xml" );
                                        if ( Resources.exists(f) ) {
                                            try {
                                                LayerInfo l = depersist(xp, f, LayerInfo.class );
                                                catalog.add( l );
                                                
                                                LOGGER.info( "Loaded layer '" + l.getName() + "'" );
                                            }
                                            catch( Exception e ) {
                                                LOGGER.log( Level.WARNING, "Failed to load cascaded wms layer '" + wl.getName() +"'", e);
                                            }
                                        }
                                    }
                                    else {
                                        LOGGER.warning( "Ignoring coverage directory " + cd.path() );
                                    }
                                }
                            } else if(!isConfigDirectory(sd)) {
                                LOGGER.warning( "Ignoring store directory '" + sd.name() +  "'");
                                continue;
                            }
                        }
                    }
                }

                //load hte layer groups for this workspace
                Resource layergroups = wsd.get("layergroups");
                if (layergroups != null) {
                    loadLayerGroups(layergroups, catalog, xp);
                }
            }
        }
        else {
            LOGGER.warning( "No 'workspaces' directory found, unable to load any stores." );
        }

        //namespaces
        
        //layergroups
        Resource layergroups = resourceLoader.get( "layergroups" );
        if ( layergroups != null ) {
           loadLayerGroups(layergroups, catalog, xp);
        }
        xp.setUnwrapNulls(true);
        catalog.resolve();
        return catalog;
    }
    
    /**
     * Some config directories in GeoServer are used to store workspace specific configurations, 
     * identify them so that we don't log complaints about their existence
     *  
     * @param f
     *
     */
    private boolean isConfigDirectory(Resource dir) {
        String name = dir.name();
        boolean result = "styles".equals(name) || "layergroups".equals(name);
        return result;
    }

    /**
     * Reads the legacy (1.x) catalog from disk.
     */
    Catalog readLegacyCatalog(Resource f, XStreamPersister xp) throws Exception {
        Catalog catalog2 = new CatalogImpl();
        catalog2.setResourceLoader(resourceLoader);
        
        //add listener now as a converter which will convert from the old style 
        // data directory to the new
        GeoServerPersister p = new GeoServerPersister( resourceLoader, xp );
        if ( !legacy ) {
            catalog2.addListener( p );
        }
        
        LegacyCatalogImporter importer = new LegacyCatalogImporter(catalog2);
        importer.setResourceLoader(resourceLoader);
        importer.imprt(resourceLoader.getBaseDirectory());
        
        if ( !legacy ) {
            catalog2.removeListener( p );
        }
        
        if ( !legacy ) {
            //copy files from old feature type directories to new
            Resource featureTypesDir = resourceLoader.get( "featureTypes" );
            if ( featureTypesDir != null ) {
                LegacyCatalogReader creader = new LegacyCatalogReader();
                creader.read( f );
                Map<String,Map<String,Object>> dataStores = creader.dataStores();
                
                for ( Resource featureTypeDir : featureTypesDir.list() ) {
                    if ( featureTypeDir.getType() != Type.DIRECTORY ) {
                        continue;
                    }
                    
                    Resource featureTypeInfo = featureTypeDir.get("info.xml" );
                    if ( !Resources.exists(featureTypeInfo) )  {
                        continue;
                    }
                    
                    LegacyFeatureTypeInfoReader reader = new LegacyFeatureTypeInfoReader();
                    reader.read( featureTypeInfo );
                    
                    Map<String,Object> dataStore = dataStores.get( reader.dataStore() );
                    if ( dataStore == null ) {
                        continue;
                    }
                    
                    String namespace = (String) dataStore.get( "namespace" );
                    Resource destFeatureTypeDir = 
                        resourceLoader.get( Paths.path("workspaces", namespace, reader.dataStore(), reader.name()) );
                    if ( destFeatureTypeDir != null ) {
                        //copy all the files over
                        for ( Resource file : featureTypeDir.list() ) {
                            if ( file.getType() == Type.RESOURCE && !featureTypeInfo.equals( file ) ) {
                                IOUtils.copy( file.in(), destFeatureTypeDir.get(file.name()).out() ) ; 
                            }
                        }
                    }
                }
            }
            
            //rename catalog.xml
            f.renameTo( f.parent().get("catalog.xml.old" ) );
        }
        
        return catalog2;
    }
    
    protected void readConfiguration(GeoServer geoServer, XStreamPersister xp) throws Exception {
        //look for services.xml, if it exists assume we are dealing with 
        // an old data directory
        Resource f = resourceLoader.get( "services.xml" );
        if (!Resources.exists(f)) {
            //assume 2.x style
            f = resourceLoader.get( "global.xml");
            if ( Resources.exists(f) ) {
                try {
                    GeoServerInfo global = depersist(xp, f, GeoServerInfo.class);
                    geoServer.setGlobal( global );
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to load global configuration file '" + f.name() + "'" , e );
                }
            }
            
            //load logging
            f = resourceLoader.get( "logging.xml" );
            if ( Resources.exists(f) ) {
                try {
                    LoggingInfo logging = depersist(xp, f, LoggingInfo.class );
                    geoServer.setLogging( logging );
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to load logging configuration file '" + f.name() + "'" , e );
                }
            }

            // load workspace specific settings
            Resource workspaces = resourceLoader.get("workspaces");
            if (Resources.exists(workspaces)) {
                for (Resource dir : workspaces.list()) {
                    if (dir.getType() != Type.DIRECTORY) continue;
    
                    f = dir.get("settings.xml");
                    if (Resources.exists(f)) {
                        try {
                            SettingsInfo settings = depersist(xp, f, SettingsInfo.class );
                            geoServer.add(settings);
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Failed to load configuration file '" + f.name() + "' for workspace " + dir.name() , e );
                        }
                    }
                }
            }

            //load services
            final List<XStreamServiceLoader> loaders = 
                GeoServerExtensions.extensions( XStreamServiceLoader.class );
            loadServices(resourceLoader.get(""), true, loaders, geoServer);

            //load services specific to workspace
            if (workspaces != null) {
                for (Resource dir : workspaces.list()) {
                    if (dir.getType() != Type.DIRECTORY) continue;

                    loadServices(dir, false, loaders, geoServer);
                }
            }
            
        } else {
            //add listener now as a converter which will convert from the old style 
            // data directory to the new
            GeoServerPersister p = new GeoServerPersister( resourceLoader, xp );
            geoServer.addListener( p );
            
            //import old style services.xml
            new LegacyConfigurationImporter(geoServer).imprt(resourceLoader.getBaseDirectory());
            
            geoServer.removeListener( p );
            
            //rename the services.xml file
            f.renameTo( f.parent().get("services.xml.old" ) );
        }
    }

    void loadStyles(Resource styles, Catalog catalog, XStreamPersister xp) {
        for ( Resource sf : Resources.list(styles, new Resources.ExtensionFilter("XML")) ) {
            try {
                //handle the .xml.xml case
                if (Resources.exists(styles.get(sf.name() + ".xml"))) {
                    continue;
                }
                
                StyleInfo s = depersist( xp, sf, StyleInfo.class );
                catalog.add( s );
                
                LOGGER.info( "Loaded style '" + s.getName() + "'" );
            }
            catch( Exception e ) {
                LOGGER.log( Level.WARNING, "Failed to load style from file '" + sf.name() + "'" , e );
            }
        }
    }

    void loadLayerGroups(Resource layergroups, Catalog catalog, XStreamPersister xp) {
        for ( Resource lgf : Resources.list( layergroups, new Resources.ExtensionFilter( "XML" ) ) ) {
            try {
                LayerGroupInfo lg = depersist( xp, lgf, LayerGroupInfo.class );
                if(lg.getLayers() == null || lg.getLayers().size() == 0) {
                    LOGGER.warning("Skipping empty layer group '" + lg.getName() + "', it is invalid");
                    continue;
                }
                catalog.add( lg );
                
                LOGGER.info( "Loaded layer group '" + lg.getName() + "'" );    
            }
            catch( Exception e ) {
                LOGGER.log( Level.WARNING, "Failed to load layer group '" + lgf.name() + "'", e );
            }
        }
    }

    void loadServices(Resource directory, boolean global, List<XStreamServiceLoader> loaders, GeoServer geoServer) {
        for ( XStreamServiceLoader<ServiceInfo> l : loaders ) {
            try {
                ServiceInfo s = l.load( geoServer, directory);
                if (!global && s.getWorkspace() == null) continue;

                geoServer.add( s );
                
                LOGGER.info( "Loaded service '" +  s.getId() + "', " + (s.isEnabled()?"enabled":"disabled") );
            }
            catch( Throwable t ) {
                if (Resources.exists(directory)) {
                    LOGGER.log(Level.SEVERE,
                            "Failed to load the service configuration in directory: " + directory
                                    + " with loader for " + l.getServiceClass(),
                            t);
                } else {
                    LOGGER.log(
                            Level.SEVERE,
                            "Failed to load the root service configuration with loader for "
                                    + l.getServiceClass(), t);
                }
            }
        }
    }

    /**
     * Helper method which uses xstream to persist an object as xml on disk.
     */
    void persist( XStreamPersister xp, Object obj, Resource f ) throws Exception {
        BufferedOutputStream out = new BufferedOutputStream( f.out() );
        xp.save( obj, out );    
        out.flush();
        out.close();
    }

    /**
     * Helper method which uses xstream to depersist an object as xml from disk.
     */
    <T> T depersist( XStreamPersister xp, Resource f , Class<T> clazz ) throws IOException {
        try(InputStream in = new ByteArrayInputStream(f.getContents())) {
            return xp.load( in, clazz );
        }
    }
    
    public void destroy() throws Exception {
        //dispose
        geoserver.dispose();
    }
}
