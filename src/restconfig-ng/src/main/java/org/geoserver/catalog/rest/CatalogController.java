/* (c) 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.catalog.rest;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.config.GeoServerDataDirectory;
import org.geoserver.rest.RestBaseController;
import org.geoserver.rest.RestException;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Base controller for catalog info requests
 */
public abstract class CatalogController extends RestBaseController {
    
    /**
     * Not an official MIME type, but GeoServer used to support it
     */
    public static final String TEXT_JSON = "text/json";
    
    /**
     * Not an official MIME type, but GeoServer used to support it
     */
    public static final String TEXT_XML = "text/xml";
    /**
     * Not an official MIME type, but GeoServer used to support it
     */
    public static final String APPLICATION_ZIP = "application/zip";
    
    public static final String MEDIATYPE_FTL_EXTENSION = "ftl";
    public static final String MEDIATYPE_FTL_VALUE = "text/plain";
    public static final MediaType MEDIATYPE_FTL = new MediaType("text","plain");
    
    protected final Catalog catalog;
    protected final GeoServerDataDirectory dataDir;

    protected final List<String> validImageFileExtensions;

    public CatalogController(Catalog catalog) {
        super();
        this.catalog = catalog;
        this.dataDir = new GeoServerDataDirectory(catalog.getResourceLoader());
        this.validImageFileExtensions = Arrays.asList("svg", "png", "jpg");
    }

    /**
     * Uses messages as a template to update resource.
     * @param message Possibly incomplete ResourceInfo used to update resource
     * @param resource Original resource (to be saved in catalog after modification)
     */
    protected void calculateOptionalFields(ResourceInfo message, ResourceInfo resource, String calculate) {
        List<String> fieldsToCalculate;
        if (calculate == null || calculate.isEmpty()) {
            boolean changedProjection = message.getSRS() == null ||
                    !message.getSRS().equals(resource.getSRS());
            boolean changedProjectionPolicy = message.getProjectionPolicy() == null ||
                    !message.getProjectionPolicy().equals(resource.getProjectionPolicy());
            boolean changedNativeBounds = message.getNativeBoundingBox() == null ||
                    !message.getNativeBoundingBox().equals(resource.getNativeBoundingBox());
            boolean changedLatLonBounds = message.getLatLonBoundingBox() == null ||
                    !message.getLatLonBoundingBox().equals(resource.getLatLonBoundingBox());
            boolean changedNativeInterpretation = changedProjectionPolicy || changedProjection;
            fieldsToCalculate = new ArrayList<String>();
            if (changedNativeInterpretation && !changedNativeBounds) {
                fieldsToCalculate.add("nativebbox");
            }
            if ((changedNativeInterpretation || changedNativeBounds) && !changedLatLonBounds) {
                fieldsToCalculate.add("latlonbbox");
            }
        } else {
            fieldsToCalculate = Arrays.asList(calculate.toLowerCase().split(","));
        }

        if (fieldsToCalculate.contains("nativebbox")) {
            CatalogBuilder builder = new CatalogBuilder(catalog);
            try {
                message.setNativeBoundingBox(builder.getNativeBounds(message));
            } catch (IOException e) {
                String errorMessage = "Error while calculating native bounds for layer: " + message;
                throw new RestException(errorMessage, HttpStatus.INTERNAL_SERVER_ERROR, e);
            }
        }
        if (fieldsToCalculate.contains("latlonbbox")) {
            CatalogBuilder builder = new CatalogBuilder(catalog);
            try {
                message.setLatLonBoundingBox(builder.getLatLonBounds(
                        message.getNativeBoundingBox(),
                        resolveCRS(message.getSRS())));
            } catch (IOException e) {
                String errorMessage =
                        "Error while calculating lat/lon bounds for featuretype: " + message;
                throw new RestException(errorMessage, HttpStatus.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    private CoordinateReferenceSystem resolveCRS(String srs) {
        if ( srs == null ) {
            return null;
        }
        try {
            return CRS.decode(srs);
        } catch(Exception e) {
            throw new RuntimeException("This is unexpected, the layer seems to be mis-configured", e);
        }
    }
}
