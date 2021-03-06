/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package com.ilscipio.scipio.product.image;

import java.io.IOException;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.cache.UtilCache;
import org.ofbiz.common.image.ImageProfile;
import org.ofbiz.common.image.ImageVariantConfig;
import com.ilscipio.scipio.content.image.ContentImageWorker;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.model.ModelUtil;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.ServiceUtil;

/**
 * SCIPIO: Image utilities for product (specifically) image handling.
 * Added 2017-07-04.
 *
 * @see com.ilscipio.scipio.content.image.ContentImageWorker
 * @see org.ofbiz.product.image.ScaleImage
 */
public abstract class ProductImageWorker {
    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String PRODUCT_IMAGEPROP_FILEPATH = "/applications/product/config/ImageProperties.xml";
    private static final UtilCache<String, Boolean> productImageEnsureCache = UtilCache.createUtilCache("product.image.ensure", true);
    private static final boolean productImageEnsureEnabled = UtilProperties.getPropertyAsBoolean("cache", "product.image.ensure.enable", false);

    protected ProductImageWorker() {
    }

    /**
     * SCIPIO: Returns the full path to the ImageProperties.xml file to use for product image size definitions.
     * Uses the one from product component if available; otherwise falls back on the generic one under content component.
     * Added 2017-07-04.
     */
    public static String getProductImagePropertiesFullPath() throws IOException {
        String path = ImageVariantConfig.getImagePropertiesFullPath(PRODUCT_IMAGEPROP_FILEPATH);
        if (new java.io.File(path).exists()) {
            return path;
        } else {
            return ContentImageWorker.getContentImagePropertiesFullPath();
        }
    }

    public static String getProductImagePropertiesPath() throws IOException {
        String path = ImageVariantConfig.getImagePropertiesFullPath(PRODUCT_IMAGEPROP_FILEPATH);
        if (new java.io.File(path).exists()) {
            return PRODUCT_IMAGEPROP_FILEPATH;
        } else {
            return ContentImageWorker.getContentImagePropertiesPath();
        }
    }

    public static void ensureProductImage(DispatchContext dctx, Locale locale, GenericValue product, String productContentTypeId, String imageUrl, boolean async, boolean useUtilCache) {
        if (!productImageEnsureEnabled) {
            return;
        }
        String cacheKey = null;
        if (useUtilCache) {
            cacheKey = product.get("productId") + "::" + productContentTypeId;
            if (Boolean.TRUE.equals(productImageEnsureCache.get(cacheKey))) {
                return;
            }
        }
        try {
            // TODO: optimize the duplicate lookups (cached anyway)
            ProductImageLocationInfo pili = ProductImageLocationInfo.from(dctx, locale,
                    product, productContentTypeId, imageUrl, null, true, false, useUtilCache);
            List<String> sizeTypeList = (pili != null) ? pili.getMissingVariantNames() : null;
            if (UtilValidate.isEmpty(sizeTypeList)) {
                if (useUtilCache) {
                    productImageEnsureCache.put(cacheKey, Boolean.TRUE);
                }
                return;
            }
            Map<String, Object> ctx = UtilMisc.toMap("productId", product.get("productId"), "productContentTypeId", productContentTypeId,
                    "sizeTypeList", sizeTypeList, "recreateExisting", true); // NOTE: the getMissingVariantNames check above (for performance) already implements the recreateExisting logic
            if (async) {
                dctx.getDispatcher().runAsync("productImageAutoRescale", ctx, false);
            } else {
                Map<String, Object> servResult = dctx.getDispatcher().runSync("productImageAutoRescale", ctx);
                if (ServiceUtil.isError(servResult)) {
                    Debug.logError("Could not trigger image variant resizing for product [" + product.get("productId") + "] productContentTypeId ["
                            + productContentTypeId + "] imageLink [" + imageUrl + "]: " + ServiceUtil.getErrorMessage(servResult), module);
                    return;
                }
            }
            if (useUtilCache) {
                productImageEnsureCache.put(cacheKey, Boolean.TRUE);
            }
        } catch(Exception e) {
            Debug.logError("Could not trigger image variant resizing for product [" + product.get("productId") + "] productContentTypeId ["
                    + productContentTypeId + "] imageLink [" + imageUrl + "]: " + e.toString(), module);
        }
    }

    public static class ImageViewType implements Serializable {
        protected final String viewType;
        protected final String viewNumber;

        protected ImageViewType(String viewType, String viewNumber) {
            this.viewType = viewType;
            this.viewNumber = viewNumber;
        }

        public static ImageViewType from(String viewType, String viewNumber) {
            return new ImageViewType(viewType, viewNumber);
        }

        public static ImageViewType from(String productContentTypeId) {
            String viewType;
            String viewNumber;
            if ("ORIGINAL_IMAGE_URL".equals(productContentTypeId) || "LARGE_IMAGE_URL".equals(productContentTypeId)
                    || "MEDIUM_IMAGE_URL".equals(productContentTypeId) || "SMALL_IMAGE_URL".equals(productContentTypeId) || "DETAIL_IMAGE_URL".equals(productContentTypeId)) {
                viewType = "main";
                viewNumber = null;
            } else if ("LARGE_IMAGE_ALT".equals(productContentTypeId) || "MEDIUM_IMAGE_ALT".equals(productContentTypeId) || "SMALL_IMAGE_ALT".equals(productContentTypeId) || "DETAIL_IMAGE_ALT".equals(productContentTypeId)) {
                viewType = "alt"; // TODO: REVIEW: where is this one used?
                viewNumber = null;
            } else if (productContentTypeId.startsWith("ADDITIONAL_IMAGE_")) {
                viewType = "additional";
                viewNumber = productContentTypeId.substring("ADDITIONAL_IMAGE_".length());
                if (viewNumber.isEmpty()) {
                    throw new IllegalArgumentException("Unsupported productContentTypeId [" + productContentTypeId
                            + "]: invalid productContentTypeId name format (ADDITIONAL_IMAGE_n)");
                }
            } else if (productContentTypeId.startsWith("XTRA_IMG_")) { // do the corresponding ADDITIONAL_IMAGE
                viewType = "additional";
                String viewInfo = productContentTypeId.substring("XTRA_IMG_".length());
                String[] parts = viewInfo.split("_", 2);
                if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    throw new IllegalArgumentException("Unsupported productContentTypeId [" + productContentTypeId
                            + "]: invalid productContentTypeId name format (XTRA_IMG_n_abc)");
                }
                viewNumber = parts[0];
            } else {
                throw new IllegalArgumentException("Unsupported productContentTypeId [" + productContentTypeId
                        + "]: invalid productContentTypeId name format (XTRA_IMG_n_abc)");
            }
            return from(viewType, viewNumber);
        }

        public String getViewType() {
            return viewType;
        }

        public String getViewNumber() {
            return viewNumber;
        }
    }

    public static Map<String, GenericValue> getOriginalImageProductContentTypes(Delegator delegator) throws GenericEntityException, IllegalArgumentException {
        Map<String, GenericValue> pctMap = new LinkedHashMap<>();
        String origPctId = "ORIGINAL_IMAGE_URL";
        GenericValue origPct = delegator.from("ProductContentType").where("productContentTypeId", origPctId).cache().queryOne();
        if (origPct == null) {
            throw new IllegalArgumentException("Could not find ProductContentType [" + origPctId + "]");
        }
        pctMap.put(origPctId, origPct);
        List<GenericValue> pctList = delegator.from("ProductContentType")
                .where(EntityCondition.makeCondition("productContentTypeId", EntityOperator.LIKE, "ADDITIONAL_IMAGE_%")).cache().queryList();
        if (pctList != null) {
            for(GenericValue pct : pctList) {
                pctMap.put(pct.getString("productContentTypeId"), pct);
            }
        }
        return pctMap;
    }

    public static Map<String, GenericValue> getImageProductContentTypes(Delegator delegator, String productContentTypeId)
            throws GenericEntityException, IllegalArgumentException {
        Map<String, GenericValue> pctMap = new LinkedHashMap<>();
        int imageNum = getImageProductContentTypeNum(productContentTypeId);
        if (imageNum == 0) {
            String origPctId = "ORIGINAL_IMAGE_URL";
            GenericValue origPct = delegator.from("ProductContentType").where("productContentTypeId", origPctId).cache().queryOne();
            if (origPct == null) {
                throw new IllegalArgumentException("Could not find ProductContentType [" + origPctId + "]");
            }
            pctMap.put(origPctId, origPct);
            List<GenericValue> pctList = delegator.from("ProductContentType")
                    .where(EntityCondition.makeCondition("productContentTypeId", EntityOperator.LIKE, "%_IMAGE_URL")).cache().queryList();
            if (pctList != null) {
                for(GenericValue pct : pctList) {
                    if (!origPctId.equals(pct.getString("productContentTypeId"))) {
                        pctMap.put(pct.getString("productContentTypeId"), pct);
                    }
                }
            }
        } else {
            String origPctId = "ADDITIONAL_IMAGE_" + imageNum;
            GenericValue origPct = delegator.from("ProductContentType").where("productContentTypeId", origPctId).cache().queryOne();
            if (origPct == null) {
                throw new IllegalArgumentException("Could not find ProductContentType [" + origPctId + "]");
            }
            pctMap.put(origPctId, origPct);
            List<GenericValue> pctList = delegator.from("ProductContentType")
                    .where(EntityCondition.makeCondition("productContentTypeId", EntityOperator.LIKE, "XTRA_IMG_" + imageNum + "_%")).cache().queryList();
            if (pctList != null) {
                for(GenericValue pct : pctList) {
                    if (!origPctId.equals(pct.getString("productContentTypeId"))) {
                        pctMap.put(pct.getString("productContentTypeId"), pct);
                    }
                }
            }
        }
        return pctMap;
    }

    public static Collection<String> getImageProductContentTypeIds(Delegator delegator, String productContentTypeId) throws GenericEntityException {
        return getImageProductContentTypes(delegator, productContentTypeId).keySet();
    }

    public static Map<String, GenericValue> getVariantProductContentDataResourceRecordsBySizeType(Delegator delegator, String productId,
                                                                                        String productContentTypeId, Timestamp moment,
                                                                                        boolean includeOriginal, boolean useCache) throws GenericEntityException, IllegalArgumentException {
        // TODO: REVIEW: this uses a heuristic to infer the sizeType, but this should be codified somewhere
        Map<String, GenericValue> sizeTypeMap = new LinkedHashMap<>();
        int imageNum = getImageProductContentTypeNum(productContentTypeId);
        if (imageNum == 0) {
            List<GenericValue> pcdrList = delegator.from("ProductContentAndDataResource")
                    .where(EntityCondition.makeCondition("productId", productId),
                            EntityCondition.makeCondition("productContentTypeId", EntityOperator.LIKE, "%_IMAGE_URL"))
                    .orderBy("-fromDate").filterByDate(moment).cache(useCache).queryList();
            if (pcdrList != null) {
                if (includeOriginal) {
                    for (GenericValue pcdr : pcdrList) { // original first
                        String pctId = pcdr.getString("productContentTypeId");
                        if ("ORIGINAL_IMAGE_URL".equals(pctId)) {
                            sizeTypeMap.put("original", pcdr);
                        }
                    }
                }
                for(GenericValue pcdr : pcdrList) {
                    String pctId = pcdr.getString("productContentTypeId");
                    if (!"ORIGINAL_IMAGE_URL".equals(pctId)) {
                        sizeTypeMap.put(getProductContentTypeImageSizeType(pctId), pcdr);
                    }
                }
            }
        } else {
            String origPctId = "ADDITIONAL_IMAGE_" + imageNum;
            if (includeOriginal) {
                GenericValue origPcdr = delegator.from("ProductContentAndDataResource")
                        .where(EntityCondition.makeCondition("productId", productId),
                                EntityCondition.makeCondition("productContentTypeId", origPctId))
                        .orderBy("-fromDate").filterByDate(moment).cache(useCache).queryFirst();
                if (origPcdr != null) {
                    sizeTypeMap.put("original", origPcdr);
                }
            }
            List<GenericValue> pctList = delegator.from("ProductContentAndDataResource")
                    .where(EntityCondition.makeCondition("productId", productId),
                            EntityCondition.makeCondition("productContentTypeId", EntityOperator.LIKE, "XTRA_IMG_" + imageNum + "_%"))
                    .orderBy("-fromDate").filterByDate(moment).cache(useCache).queryList();
            if (pctList != null) {
                for(GenericValue pcdr : pctList) {
                    String pctId = pcdr.getString("productContentTypeId");
                    sizeTypeMap.put(getProductContentTypeImageSizeType(pctId), pcdr);
                }
            }
        }
        return sizeTypeMap;
    }

    public static Map<String, GenericValue> getImageSizeProductContentTypes(Delegator delegator, String productContentTypeId) throws GenericEntityException, IllegalArgumentException {
        Map<String, GenericValue> sizePctMap = new LinkedHashMap<>();
        for(Map.Entry<String, GenericValue> entry : getImageSizeProductContentTypes(delegator, productContentTypeId).entrySet()) {
            sizePctMap.put(getProductContentTypeImageSizeType(entry.getValue()), entry.getValue());
        }
        return sizePctMap;
    }

    /**
     * Gets a sizeType for an image size variant ProductContentType.
     * TODO: ProductContentType should support this directly on the entity.
     */
    public static String getProductContentTypeImageSizeType(GenericValue pct) throws IllegalArgumentException {
        return getProductContentTypeImageSizeType(pct.getString("productContentTypeId"));
    }

    // TODO: REMOVE: this is a hack for now, see above
    protected static String getProductContentTypeImageSizeType(String productContentTypeId) throws IllegalArgumentException {
        if (productContentTypeId.equals("ORIGINAL_IMAGE_URL") || productContentTypeId.startsWith("ADDITIONAL_IMAGE_")) {
            return "original";
        } else if (productContentTypeId.endsWith("_IMAGE_URL")) {
            return productContentTypeId.substring(0, productContentTypeId.length() - "_IMAGE_URL".length()).toLowerCase();
        } else if (productContentTypeId.startsWith("XTRA_IMG_")) {
            return productContentTypeId.substring(productContentTypeId.lastIndexOf('_')).toLowerCase();
        } else {
            throw new IllegalArgumentException("Unrecognized image productContentTypeId: " + productContentTypeId);
        }
    }

    public static GenericValue getImageSizeTypeProductContentType(Delegator delegator, int imageNum, String sizeType) throws GenericEntityException, IllegalArgumentException {
        return delegator.from("ProductContentType").where("productContentTypeId", getImageSizeTypeProductContentTypeId(imageNum, sizeType))
                .cache().queryOne();
    }

    public static GenericValue getImageSizeTypeProductContentType(Delegator delegator, String productContentTypeId, String sizeType) throws GenericEntityException, IllegalArgumentException {
        return getImageSizeTypeProductContentType(delegator, getImageProductContentTypeNum(productContentTypeId), sizeType);
    }

    protected static String getImageSizeTypeProductContentTypeId(int imageNum, String sizeType) throws IllegalArgumentException {
        if (imageNum == 0) {
            return sizeType.toUpperCase() + "_IMAGE_URL";
        } else if (imageNum > 0) {
            return "original".equalsIgnoreCase(sizeType) ? "ADDITIONAL_IMAGE_" + imageNum : "XTRA_IMG_" + imageNum + "_" + sizeType.toUpperCase();
        } else {
            throw new IllegalArgumentException("Invalid image number: " + imageNum);
        }
    }

    protected static String getImageSizeTypeProductContentTypeId(String productContentTypeId, String sizeType) throws IllegalArgumentException {
        return getImageSizeTypeProductContentTypeId(getImageProductContentTypeNum(productContentTypeId), sizeType);
    }

    public static int getImageProductContentTypeNum(String productContentTypeId) throws IllegalArgumentException {
        if (productContentTypeId.endsWith("_IMAGE_URL")) {
            return 0;
        } else if (productContentTypeId.startsWith("ADDITIONAL_IMAGE_")) {
            return Integer.parseInt(productContentTypeId.substring("ADDITIONAL_IMAGE_".length()));
        } else if (productContentTypeId.startsWith("XTRA_IMG_")) {
            int sepIndex = productContentTypeId.indexOf('_', "XTRA_IMG_".length());
            if (sepIndex <= 0) {
                throw new IllegalArgumentException("Unrecognized image productContentTypeId (expected format: XTRA_IMG_%_%): " + productContentTypeId);
            }
            return Integer.parseInt(productContentTypeId.substring("XTRA_IMG_".length(), sepIndex));
        } else {
            throw new IllegalArgumentException("Unrecognized image productContentTypeId (expected %_IMAGE_URL, ADDITIONAL_IMAGE_%, XTRA_IMG_%_%): " + productContentTypeId);
        }
    }

    /** Migration and backward-compatibility, see ProductTypeData.xml */
    public static void ensureParentProductContentTypeImageUrlRecords(Delegator delegator) throws GenericEntityException {
        GenericValue imageUrl = delegator.findOne("ProductContentType", UtilMisc.toMap("productContentTypeId", "IMAGE_URL_BASE"), false);
        if (imageUrl == null) {
            delegator.makeValue("ProductContentType", "productContentTypeId", "IMAGE_URL_BASE",
                    "hasTable", "N", "description", "Image - Base").create();
        }
        GenericValue fullImageUrl = delegator.findOne("ProductContentType", UtilMisc.toMap("productContentTypeId", "IMAGE_URL_FULL"), false);
        if (fullImageUrl == null) {
            delegator.makeValue("ProductContentType", "productContentTypeId", "IMAGE_URL_FULL",
                    "hasTable", "N", "description", "Image - Full", "parentTypeId", "IMAGE_URL_BASE").create();
        }
        GenericValue variantImageUrl = delegator.findOne("ProductContentType", UtilMisc.toMap("productContentTypeId", "IMAGE_URL_VARIANT"), false);
        if (variantImageUrl == null) {
            delegator.makeValue("ProductContentType", "productContentTypeId", "IMAGE_URL_VARIANT",
                    "hasTable", "N", "description", "Image - Variant", "parentTypeId", "IMAGE_URL_BASE").create();
        }
    }

    /** Migration and backward-compatibility, see ProductTypeData.xml */
    public static GenericValue createProductContentTypeImageUrlRecord(Delegator delegator, String productContentTypeId, String parentTypeId, String sizeType) throws GenericEntityException {
        ensureParentProductContentTypeImageUrlRecords(delegator);
        int imageNum = getImageProductContentTypeNum(productContentTypeId);
        String sizeTypeName = sizeType.substring(0, 1).toUpperCase() + sizeType.substring(1);
        String description = (imageNum >= 1) ? "Image - Additional View " + imageNum + " " + sizeTypeName : "Image - " + sizeTypeName;
        return delegator.makeValue("ProductContentType", "productContentTypeId", productContentTypeId,
                "hasTable", "N", "description", description, "parentTypeId", parentTypeId).create();
    }

    public static String getDataResourceImageUrl(GenericValue dataResource, boolean useEntityCache) throws GenericEntityException { // SCIPIO
        if (dataResource == null) {
            return null;
        }
        String dataResourceTypeId = dataResource.hasModelField("drDataResourceTypeId") ?
                dataResource.getString("drDataResourceTypeId") : dataResource.getString("dataResourceTypeId");
        if ("SHORT_TEXT".equals(dataResourceTypeId)) {
            return dataResource.hasModelField("drObjectInfo") ?
                    dataResource.getString("drObjectInfo") : dataResource.getString("objectInfo");
        } else if ("ELECTRONIC_TEXT".equals(dataResourceTypeId)) {
            String dataResourceId = dataResource.hasModelField("drDataResourceId") ?
                    dataResource.getString("drDataResourceId") : dataResource.getString("dataResourceId");
            GenericValue elecText = dataResource.getDelegator().findOne("ElectronicText", UtilMisc.toMap("dataResourceId", dataResourceId), useEntityCache);
            if (elecText != null) {
                return elecText.getString("textData");
            }
        }
        return null;
    }

    public static String getProductInlineImageFieldPrefix(Delegator delegator, String productContentTypeId) {
        String candidateFieldName = ModelUtil.dbNameToVarName(productContentTypeId);
        ModelEntity productModel = delegator.getModelEntity("Product");
        if (productModel.isField(candidateFieldName) && candidateFieldName.endsWith("Url")) {
            return candidateFieldName.substring(0, candidateFieldName.length() - "Url".length());
        }
        return null;
    }

    public static String getDefaultProductImageProfileName(Delegator delegator, String productContentTypeId) {
        return "IMAGE_PRODUCT-" + productContentTypeId;
    }

    public static ImageProfile getProductImageProfileOrDefault(Delegator delegator, String productContentTypeId, GenericValue product, GenericValue content, boolean useEntityCache, boolean useProfileCache) {
        String profileName;
        ImageProfile profile;
        if (content != null) {
            profileName = content.getString("mediaProfile");
            if (profileName != null) {
                profile = ImageProfile.getImageProfile(delegator, profileName, useProfileCache);
                if (profile != null) {
                    return profile;
                } else {
                    // Explicitly named missing profile is always an error
                    Debug.logError("Could not find image profile [" + profileName + "] in mediaprofiles.properties from " +
                            "Content.mediaProfile for content [" + content.get("contentId") + "] productContentTypeId [" + productContentTypeId +
                            "] product [" + product.get("productId") + "]", module);
                    return null;
                }
            }
        } else if (product != null) {
            profileName = product.getString("imageProfile");
            if (profileName != null) {
                profile = ImageProfile.getImageProfile(delegator, profileName, useProfileCache);
                if (profile != null) {
                    return profile;
                } else {
                    // Explicitly named missing profile is always an error
                    Debug.logError("Could not find image profile [" + profileName + "] in mediaprofiles.properties from " +
                            "Product.imageProfile for product [" + product.get("productId") + "] productContentTypeId [" + productContentTypeId + "]", module);
                    return null;
                }
            }
        }
        profileName = getDefaultProductImageProfileName(delegator, productContentTypeId);
        profile = ImageProfile.getImageProfile(delegator, profileName, useProfileCache);
        if (profile != null) {
            return profile;
        } else {
            // Clients may add extra productContentTypeId and they should add mediaprofiles.properties definitions
            Debug.logWarning("Could not find default image profile [" + profileName + "] in mediaprofiles.properties from " +
                    "productContentTypeId [" + productContentTypeId + "]; using IMAGE_PRODUCT", module);
        }
        profile = ImageProfile.getImageProfile(delegator, "IMAGE_PRODUCT", useProfileCache);
        if (profile != null) {
            return profile;
        } else {
            // Should not happen
            Debug.logError("Could not find image profile IMAGE_PRODUCT in mediaprofiles.properties; fatal error", module);
            return null;
        }
    }

    public static ImageProfile getDefaultProductImageProfile(Delegator delegator, String productContentTypeId, boolean useEntityCache, boolean useProfileCache) {
        return getProductImageProfileOrDefault(delegator, productContentTypeId, null, null, useEntityCache, useProfileCache);
    }
}
