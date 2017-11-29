package com.ilscipio.scipio.product.seo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntity;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.product.category.CategoryContentWrapper;
import org.ofbiz.product.product.ProductContentWrapper;
import org.ofbiz.product.product.ProductWorker;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ModelService;
import org.ofbiz.service.ServiceUtil;

import com.ilscipio.scipio.product.category.CatalogAltUrlSanitizer;
import com.ilscipio.scipio.product.category.CatalogUrlType;
import com.ilscipio.scipio.product.seo.SeoCatalogUrlExporter.ExportDataFileConfig;
import com.ilscipio.scipio.product.seo.SeoCatalogUrlExporter.ExportTraversalConfig;
import com.ilscipio.scipio.product.seo.SeoCatalogUrlGenerator.GenTraversalConfig;

/**
 * SCIPIO: SEO catalog services.
 * <p>
 * TODO: localize error msgs
 * TODO?: other child product associations (only virtual-variant covered)
 */
public abstract class SeoCatalogServices {

    public static final String module = SeoCatalogServices.class.getName();
    
    private static final String logPrefix = "Seo: ";
    
    private static final String productNameField = "PRODUCT_NAME";
    private static final String categoryNameField = "CATEGORY_NAME";
    
    protected SeoCatalogServices() {
    }

    /**
     * Returned instance can only perform generic (non-website-specific) operations. 
     */
    private static CatalogAltUrlSanitizer getCatalogAltUrlSanitizer(DispatchContext dctx, Map<String, ? extends Object> context) {
        return SeoCatalogUrlWorker.getDefaultInstance(dctx.getDelegator()).getCatalogAltUrlSanitizer();
    }
    
    /**
     * Re-generates alternative urls for product based on the ruleset outlined in SeoConfig.xml.
     * TODO: localize error msgs
     * <p>
     * TODO: REVIEW: the defaultLocalString is NOT trivial to retrieve, for now we simply use
     * the one from the source ProductContent text IF there is one, but if there is not
     * then it is left empty because it's too hard to retrieve for now (same problem as Solr).
     */
    public static Map<String, Object> generateProductAlternativeUrls(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        //LocalDispatcher dispatcher = dctx.getDispatcher();
        
        boolean replaceExisting = !Boolean.FALSE.equals(context.get("replaceExisting"));
        boolean removeOldLocales = !Boolean.FALSE.equals(context.get("removeOldLocales"));
        Timestamp moment = UtilDateTime.nowTimestamp();
        final boolean useCache = false; // probably bad idea
        boolean doChildProducts = Boolean.TRUE.equals(context.get("doChildProducts"));
        boolean includeVariant = !Boolean.FALSE.equals(context.get("includeVariant"));

        GenericEntity productEntity = (GenericEntity) context.get("product");
        try {
            GenericValue product;
            if (productEntity instanceof GenericValue) {
                product = (GenericValue) productEntity;
            } else {
                String productId = (productEntity != null) ? productEntity.getString("productId") : (String) context.get("productId");
                product = delegator.findOne("Product", UtilMisc.toMap("productId", productId), useCache);
                if (product == null) {
                    return ServiceUtil.returnError("product not found for ID: " + productId);
                }
            }
            return generateProductAlternativeUrls(dctx, context, product, new ArrayList<GenProdAltUrlParentEntry>(),
                    replaceExisting, removeOldLocales, moment, doChildProducts, includeVariant, useCache);
        } catch (Exception e) {
            String message = "Error while generating alternative links: " + e.getMessage();
            Debug.logError(e, logPrefix+message, module);
            Map<String, Object> result = ServiceUtil.returnError(message);
            // NOTE: this is simplified by using only one transaction; otherwise would have real numbers here
            result.put("numUpdated", 0);
            result.put("numSkipped", 0);
            result.put("numError", 1);
            return result;
        }
    }

    static class GenProdAltUrlParentEntry {
        public GenericValue product;
        public GenericValue nameProductContent;
        public String productAssocTypeId;
        boolean virtualVariant;
        
        public GenProdAltUrlParentEntry(GenericValue product, GenericValue nameProductContent, boolean virtualVariant) {
            this.product = product;
            this.nameProductContent = nameProductContent;
            if (virtualVariant) {
                this.productAssocTypeId = "PRODUCT_VARIANT"; 
            }
            this.virtualVariant = virtualVariant;
        }
        
        public boolean isVirtualVariant() {
            return virtualVariant;
        }
    }
    
    static Map<String, Object> generateProductAlternativeUrls(DispatchContext dctx, Map<String, ?> context,
            GenericValue product, List<GenProdAltUrlParentEntry> parentProducts, boolean replaceExisting, boolean removeOldLocales, Timestamp moment, 
            boolean doChildProducts, boolean includeVariant, boolean useCache) throws Exception {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String productId = product.getString("productId");
        
        // lookup existing alternative url
        GenericValue productContent = null;
        List<GenericValue> altUrlContent = EntityQuery.use(delegator).from("ProductContent")
                .where("productId", productId, "productContentTypeId", "ALTERNATIVE_URL").filterByDate().cache(useCache).queryList();
        if (UtilValidate.isNotEmpty(altUrlContent)) {
            if (!replaceExisting) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("numUpdated", (int) 0);
                result.put("numSkipped", (int) 1);
                result.put("numError", (int) 0);
                return result;
            }
            productContent = altUrlContent.get(0);
        }
        
        GenericValue mainContent = null;
        
        // get names by locale, and the main content record info
        String mainLocaleString = null;
        GenericValue nameMainContent = null;
        Map<String, String> localeTextMap = null;
        
        GenericValue nameProductContent = EntityQuery.use(delegator).from("ProductContent")
                .where("productId", productId, "productContentTypeId", productNameField).filterByDate(moment).cache(useCache).queryFirst();
        if (nameProductContent == null) {
            // CHECK VIRTUAL PRODUCT for source texts - see ProductContentWrapper (emulation)
            // TODO: REVIEW: like ProductContentWrapper, this only checks one virtual level up
            if ("Y".equals(product.getString("isVariant"))) {
                // NEW OPTIMIZATION: can check the passed parent, LAST ONLY to follow ProductContentWrapper
                GenProdAltUrlParentEntry parentProductEntry = UtilValidate.isNotEmpty(parentProducts) ? parentProducts.get(parentProducts.size() - 1) : null;
                if (parentProductEntry != null && parentProductEntry.isVirtualVariant()) {
                    // TODO?: future?: deep support would need to be implemented everywhere
                    //ListIterator<GenProdAltUrlParentEntry> it = parentProducts.listIterator(parentProducts.size());
                    //while(it.hasPrevious() && nameProductContent == null) {
                    //    GenProdAltUrlParentEntry parentProductEntry = it.previous();
                    //    if (parentProductEntry != null) {
                    //        nameProductContent = parentProductEntry.nameProductContent;
                    //    }
                    //}
                    nameProductContent = parentProductEntry.nameProductContent;
                } else {
                    GenericValue parentProduct = ProductWorker.getParentProduct(productId, delegator, useCache);
                    if (parentProduct != null) {
                        nameProductContent = EntityQuery.use(delegator).from("ProductContent")
                                .where("productId", parentProduct.getString("productId"), "productContentTypeId", productNameField).filterByDate(moment).cache(useCache).queryFirst();
                    }
                }
            }
        }
        if (nameProductContent != null) {
            nameMainContent = nameProductContent.getRelatedOne("Content", useCache);
            localeTextMap = getSimpleTextsByLocaleString(delegator, dispatcher, 
                    nameMainContent, moment, useCache);
            mainLocaleString = nameMainContent.getString("localeString");
        }
        
        // make seo names for assoc records
        CatalogAltUrlSanitizer sanitizer = getCatalogAltUrlSanitizer(dctx, context);
        Map<String, String> localeUrlMap = (localeTextMap != null) ? sanitizer.convertNamesToDbAltUrls(localeTextMap, CatalogUrlType.PRODUCT) : Collections.<String, String>emptyMap();
        
        // make seo name for main record (may or may not already be in localeUrlMap)
        String mainUrl = determineMainRecordUrl(delegator, dispatcher, sanitizer, CatalogUrlType.PRODUCT,
                nameMainContent, mainLocaleString, localeUrlMap, product, productNameField, useCache);
        
        // store 
        if (productContent != null) {
            mainContent = productContent.getRelatedOne("Content", useCache);
        }
        mainContent = replaceAltUrlContentLocalized(delegator, dispatcher, context, 
                mainContent, mainLocaleString, mainUrl, localeUrlMap, removeOldLocales, moment);
        
        if (productContent == null) {
            productContent = delegator.makeValue("ProductContent");
            productContent.put("productId", productId);
            productContent.put("contentId", mainContent.getString("contentId"));
            productContent.put("productContentTypeId", "ALTERNATIVE_URL");
            productContent.put("fromDate", UtilDateTime.nowTimestamp());
            //productContent.put("sequenceNum", new Long(1)); // no
            productContent = delegator.create(productContent);
        }
        
        int numUpdated = 1;
        int numSkipped = 0;
        int numError = 0;
        
        if (doChildProducts) {
            if (includeVariant && "Y".equals(product.getString("isVirtual"))) {
                // we can pass ourselves as the parent to the variant, allows to skip some lookups
                parentProducts.add(new GenProdAltUrlParentEntry(product, nameProductContent, true));
                try {
                    List<GenericValue> variantAssocList = EntityQuery.use(dctx.getDelegator()).from("ProductAssoc")
                            .where("productId", productId, "productAssocTypeId", "PRODUCT_VARIANT").filterByDate().cache(useCache).queryList();
                    if (variantAssocList.size() > 0) {
                        if (Debug.infoOn()) Debug.logInfo(logPrefix+"generateProductAlternativeUrls: virtual product '" + productId 
                                + "' has " + variantAssocList.size() + " variants for alternative URL generation", module);
                        
                        for(GenericValue variantAssoc : variantAssocList) {
                            GenericValue variantProduct = variantAssoc.getRelatedOne("AssocProduct", useCache);
                            
                            Map<String, Object> childResult = generateProductAlternativeUrls(dctx, context, 
                                    variantProduct, parentProducts, replaceExisting, removeOldLocales, 
                                    moment, doChildProducts, includeVariant, useCache);
                            
                            // NOTE: most of this
                            Integer childrenUpdated = (Integer) childResult.get("numUpdated");
                            Integer childrenSkipped = (Integer) childResult.get("numSkipped");
                            Integer childrenError = (Integer) childResult.get("numError");
                            if (childrenUpdated != null) numUpdated += childrenUpdated;
                            if (childrenSkipped != null) numSkipped += childrenSkipped;
                            if (ServiceUtil.isSuccess(childResult)) {
                                if (childrenError != null) numError += childrenError;
                            } else {
                                if (childrenError != null) numError += childrenError;
                                else numError++; // count could be missing in error case
                            }
                        }
                    }
                } finally {
                    parentProducts.remove(parentProducts.size() - 1);
                }
            }
            
            // TODO?: handle other child product associations here? (non-virtual/variant)
            // NOTE: the only other one handled by ProductContentWrapper is UNIQUE_ITEM,
            // and practically nothing appears to use it
        }
        
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("mainContentId", mainContent.getString("contentId"));
        result.put("numUpdated", numUpdated);
        result.put("numSkipped", numSkipped);
        result.put("numError", numError);
        return result;
    }
    
    /**
     * SCIPIO: Re-generates alternative urls for category based on the ruleset outlined in SeoConfig.xml.
     */
    public static Map<String, Object> generateProductCategoryAlternativeUrls(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        //LocalDispatcher dispatcher = dctx.getDispatcher();
        
        boolean replaceExisting = !Boolean.FALSE.equals(context.get("replaceExisting"));
        boolean removeOldLocales = !Boolean.FALSE.equals(context.get("removeOldLocales"));
        Timestamp moment = UtilDateTime.nowTimestamp();
        final boolean useCache = false; // probably bad idea
        
        GenericEntity productCategoryEntity = (GenericEntity) context.get("productCategory");
        try {
            GenericValue productCategory;
            if (productCategoryEntity instanceof GenericValue) {
                productCategory = (GenericValue) productCategoryEntity;
            } else {
                String productCategoryId = (productCategoryEntity != null) ? productCategoryEntity.getString("productCategoryId") : (String) context.get("productCategoryId");
                productCategory = delegator.findOne("ProductCategory", UtilMisc.toMap("productCategoryId", productCategoryId), useCache);
                if (productCategory == null) {
                    return ServiceUtil.returnError("category not found for ID: " + productCategoryId);
                }
            }
            return generateProductCategoryAlternativeUrls(dctx, context, 
                    productCategory, replaceExisting, removeOldLocales, moment, useCache);
        } catch (Exception e) {
            String message = "Error while generating alternative links: " + e.getMessage();
            Debug.logError(e, logPrefix+message, module);
            return ServiceUtil.returnError(message);
        }
    }
    
    static Map<String, Object> generateProductCategoryAlternativeUrls(DispatchContext dctx, Map<String, ?> context,
            GenericValue productCategory, boolean replaceExisting, boolean removeOldLocales, Timestamp moment, boolean useCache) throws Exception {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String productCategoryId = productCategory.getString("productCategoryId");
        
        // lookup existing alternative url
        GenericValue productCategoryContent = null;
        List<GenericValue> altUrlContent = EntityQuery.use(delegator).from("ProductCategoryContent")
                .where("productCategoryId", productCategoryId, "prodCatContentTypeId", "ALTERNATIVE_URL").filterByDate().cache(useCache).queryList();
        if (UtilValidate.isNotEmpty(altUrlContent)) {
            if (!replaceExisting) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("categoryUpdated", Boolean.FALSE);
                return result;
            }
            productCategoryContent = altUrlContent.get(0);
        }
        
        GenericValue mainContent = null;
        
        // get names by locale, and the main content record info
        String mainLocaleString = null;
        GenericValue nameMainContent = null;
        Map<String, String> localeTextMap = null;
        
        GenericValue nameProductCategoryContent = EntityQuery.use(delegator).from("ProductCategoryContent")
                .where("productCategoryId", productCategoryId, "prodCatContentTypeId", categoryNameField).filterByDate(moment).cache(useCache).queryFirst();
        if (nameProductCategoryContent != null) {
            nameMainContent = nameProductCategoryContent.getRelatedOne("Content", useCache);
            localeTextMap = getSimpleTextsByLocaleString(delegator, dispatcher, 
                    nameMainContent, moment, useCache);
            mainLocaleString = nameMainContent.getString("localeString"); 
        }
        
        // make seo names for assoc records
        CatalogAltUrlSanitizer sanitizer = getCatalogAltUrlSanitizer(dctx, context);
        Map<String, String> localeUrlMap = (localeTextMap != null) ? sanitizer.convertNamesToDbAltUrls(localeTextMap, CatalogUrlType.CATEGORY) : Collections.<String, String>emptyMap();
        
        // make seo name for main record (may or may not already be in localeUrlMap)
        String mainUrl = determineMainRecordUrl(delegator, dispatcher, sanitizer, CatalogUrlType.CATEGORY,
                nameMainContent, mainLocaleString, localeUrlMap, productCategory, categoryNameField, useCache);

        // store
        if (productCategoryContent != null) {
            mainContent = productCategoryContent.getRelatedOne("Content", useCache);
        }
        mainContent = replaceAltUrlContentLocalized(delegator, dispatcher, context, 
                mainContent, mainLocaleString, mainUrl, localeUrlMap, removeOldLocales, moment);
    
        if (productCategoryContent == null) {
            productCategoryContent = delegator.makeValue("ProductCategoryContent");
            productCategoryContent.put("productCategoryId", productCategoryId);
            productCategoryContent.put("contentId", mainContent.getString("contentId"));
            productCategoryContent.put("prodCatContentTypeId", "ALTERNATIVE_URL");
            productCategoryContent.put("fromDate", UtilDateTime.nowTimestamp());
            //productCategoryContent.put("sequenceNum", new Long(1)); // no
            productCategoryContent = delegator.create(productCategoryContent);
        }
        
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("mainContentId", mainContent.getString("contentId"));
        result.put("categoryUpdated", Boolean.TRUE);
        return result;
    }
    
    /**
     * Determines an alt URL value to use for the main ALTERNATIVE_URL Content record (to which
     * ALTERNATE_LOCALE Content records are associated).
     * It may have an explicit localeString or be null, in which case handling possibilities are different.
     * The localeString is copied directly from the source nameMainContent
     */
    private static String determineMainRecordUrl(Delegator delegator, LocalDispatcher dispatcher, CatalogAltUrlSanitizer sanitizer, CatalogUrlType urlType,
            GenericValue nameMainContent, String mainLocaleString, Map<String, String> localeUrlMap, 
            GenericValue prodOrCatEntity, String entityNameField, boolean useCache) throws GeneralException, IOException {
        
        if (UtilValidate.isNotEmpty(mainLocaleString)) {
            String mainUrl = localeUrlMap.get(mainLocaleString);
            // NOTE: HERE, MUST RETURN EVEN IF EMPTY: 
            // it is feasible that we could end up with mainUrl here if the source nameMainContent
            // was not filled in properly - but we CANNOT set anything else, because we have no way
            // to know if the non-localized fields are really in the locale of mainLocaleString.
            // So, this is a case where user will simply have to fix his error (an empty ElectronicText).
            //if (UtilValidate.isNotEmpty(mainUrl)) {
            //    return mainUrl;
            //}
            return mainUrl;
        } 
            
        // Here, there is no locale specified, so we can use any text that has no locale associated.
        String mainText;
        
        // try CATEGORY_NAME/PRODUCT_NAME main ALTERNATIVE_URL Content record textData,
        // where localeString was null
        if (nameMainContent != null) {
            mainText = getContentElectronicText(delegator, dispatcher, nameMainContent).getString("textData");
            if (UtilValidate.isNotEmpty(mainText)) {
                return sanitizer.convertNameToDbAltUrl(mainText, null, urlType);
            }
        }
      
        // try ProductCategory.categoryName/Product.productName non-localized field
        if (urlType == CatalogUrlType.CATEGORY) {
            mainText = CategoryContentWrapper.getEntityFieldValue(prodOrCatEntity, categoryNameField, delegator, dispatcher, useCache);
        } else {
            mainText = ProductContentWrapper.getEntityFieldValue(prodOrCatEntity, productNameField, delegator, dispatcher, useCache);
        }
        if (UtilValidate.isNotEmpty(mainText)) {
            return sanitizer.convertNameToDbAltUrl(mainText, null, urlType);
        }
        
        // NOTE: will not fallback on productCategoryId/productId here - this empty textData
        // should get detected by the link generation code in SeoCatalogUrlWorker and
        // it will append an ID as a fallback - it is probably better to do that
        // because setting ID here could be masking the issue at the same time as
        // causing potiential double IDs in URL parts...
        Debug.logWarning(logPrefix+"Could not determine a SEO alternative URL value for ALTERNATIVE_URL primary Content record "
                + " for " + prodOrCatEntity.getEntityName() + " '" + prodOrCatEntity.getPkShortValueString() + "'"
                + "; no valid XXX_NAME Content records or xxxName entity fields are available; record's textData will be empty", module);
        
        return null;
    }

    /**
     * TODO: move this, I need it elsewhere too...
     */
    private static Map<String, String> getSimpleTextsByLocaleString(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue mainContent, Timestamp moment, boolean useCache) throws GenericEntityException {
        Map<String, String> localeTextMap = new HashMap<>();
        
        List<GenericValue> assocViewList = EntityQuery.use(delegator).from("ContentAssocToElectronicText")
                .where("contentIdStart", mainContent.getString("contentId"), 
                        "contentAssocTypeId", "ALTERNATE_LOCALE").filterByDate(moment).cache(useCache).queryList();
        for(GenericValue assocView : assocViewList) {
            String assocLocaleString = assocView.getString("localeString");
            if (UtilValidate.isNotEmpty(assocLocaleString)) {
                localeTextMap.put(assocLocaleString, assocView.getString("textData"));
            }
        }
        
        String mainLocaleString = mainContent.getString("localeString");
        if (UtilValidate.isNotEmpty(mainLocaleString)) {
            GenericValue elecText = getContentElectronicText(delegator, dispatcher, mainContent);
            String mainTextData = elecText.getString("textData");
            if (UtilValidate.isNotEmpty(mainTextData)) {
                localeTextMap.put(mainLocaleString, mainTextData);
            }
        }
        
        return localeTextMap;
    }
    
    /**
     * Replaces the textData of the the ALTERNATIVE_URL Content and its associated ALTERNATE_LOCALE Contents
     * using mapping by following the localeString mappings of the source CATEGORY_NAME/PRODUCT_NAME
     * Content records and trying to maintain a similar layout.
     * Relies on a 1-to-1 mapping of CATEGORY_NAME/PRODUCT_NAME-to-ALTERNATIVE_URL Content records;
     * the only thing that may be different is the order of the associated records, while the main
     * (non-assoc) record must correspond to the source if one exists.
     * <p>
     * NOTE: The main Content record's localeString is ALWAYS set to the localeString of the
     * CATEGORY_NAME/PRODUCT_NAME main Content record - this is the only way to keep a consistent
     * pattern and allows to avoid dependency on an external defaultLocaleString (in other
     * words don't need to try to determine ProductStore.defaultLocaleString).
     * <p>
     * TODO?: move elsewhere, may need to reuse...
     */
    private static GenericValue replaceAltUrlContentLocalized(Delegator delegator, LocalDispatcher dispatcher, Map<String, ?> context,
            GenericValue mainContent, String mainLocaleString, String mainUrl, Map<String, String> localeUrlMap,
            boolean removeOldLocales, Timestamp moment) throws Exception {
        // DEV NOTE: to simplify, I am setting removeDupLocales to same as removeOldLocales...
        // as long as removal is understood, there is really no reason to keep duplicates because
        // the prior code was setting them all to the exact same value, so in other words,
        // having removeDupLocales false was completely pointless...
        final boolean removeDupLocales = removeOldLocales;
        
        Set<String> remainingLocales = new HashSet<>(localeUrlMap.keySet());

        // update main content
        if (mainContent == null) {
            mainContent = createAltUrlSimpleTextContent(delegator, dispatcher, mainLocaleString, mainUrl);
        } else {
            updateAltUrlSimpleTextContent(delegator, dispatcher, mainContent, mainLocaleString, mainUrl);
        }
        
        // SPECIAL: 2017-11-28: we must remove the mainLocaleString from remainingLocales because
        // 1) when creating brand new records, don't want the second loop to create new ALTERNATE_LOCALEs
        //    for locale already covered in the main record, and
        // 2) if we remove mainLocaleString from remainingLocales BEFORE the next "update in-place or remove" loop,
        //    it will allow the loop to correct past mistakes and remove duplicates that were previously created
        if (UtilValidate.isNotEmpty(mainLocaleString)) {
            remainingLocales.remove(mainLocaleString);
        }
        
        List<GenericValue> contentAssocList = EntityQuery.use(delegator).from("ContentAssoc")
                .where("contentId", mainContent.getString("contentId"), "contentAssocTypeId", "ALTERNATE_LOCALE")
                .filterByDate(moment).cache(false).queryList();
        
        // update in-place or remove existing assoc records
        for(GenericValue contentAssoc : contentAssocList) {
            GenericValue content = contentAssoc.getRelatedOne("ToContent", false);
            String localeString = content.getString("localeString");

            // Removal logic:
            // * The localeString was "old" if it's not in localeUrlMap at all.
            //   * We also "treat" localeString also as "old" if it's in localeUrlMap but empty value.
            // * The localeString was a "duplicate" if it's in localeUrlMap but was removed from remainingLocales.
            
            String textData = localeUrlMap.get(localeString);
            if (UtilValidate.isNotEmpty(textData)) {
                if (!removeDupLocales || remainingLocales.contains(localeString)) {
                    updateAltUrlSimpleTextContent(delegator, dispatcher, content, textData);
                    remainingLocales.remove(localeString);
                } else {
                    removeContentAndRelated(delegator, dispatcher, context, content);
                }
            } else {
                if (removeOldLocales) {
                    removeContentAndRelated(delegator, dispatcher, context, content);
                }
            }
        }
        
        // see above comment - could have done this code here, but think it works better if earlier
        //if (UtilValidate.isNotEmpty(mainLocaleString)) {
        //    remainingLocales.remove(mainLocaleString);
        //}
        
        // create new assoc records
        for(String localeString : remainingLocales) {
            String textData = localeUrlMap.get(localeString);
            if (UtilValidate.isNotEmpty(textData)) {
                GenericValue content = createAltUrlSimpleTextContent(delegator, dispatcher, localeString, textData);
                GenericValue contentAssoc = delegator.makeValue("ContentAssoc");
                contentAssoc.put("contentId", mainContent.getString("contentId"));
                contentAssoc.put("contentIdTo", content.getString("contentId"));
                contentAssoc.put("fromDate", moment);
                contentAssoc.put("contentAssocTypeId", "ALTERNATE_LOCALE");
                contentAssoc = delegator.create(contentAssoc);
            }
        }

        return mainContent;
    }
    
    private static void removeContentAndRelated(Delegator delegator, LocalDispatcher dispatcher, Map<String, ?> context, GenericValue content) throws GeneralException {
        Map<String, Object> servCtx = new HashMap<>();
        servCtx.put("userLogin", context.get("userLogin"));
        servCtx.put("locale", context.get("locale"));
        servCtx.put("contentId", content.get("contentId"));
        Map<String, Object> servResult = dispatcher.runSync("removeContentAndRelated", servCtx);
        if (ServiceUtil.isError(servResult)) {
            throw new SeoCatalogException("Cannot remove ALTERNATE_LOCALE record contentId '" 
                    + content.get("contentId") + "': " + ServiceUtil.getErrorMessage(servResult));
        }
    }
    
    private static GenericValue createAltUrlSimpleTextContent(Delegator delegator, LocalDispatcher dispatcher, String localeString, String textData) throws GenericEntityException {
        // create dataResource
        GenericValue dataResource = delegator.makeValue("DataResource");
        dataResource.put("dataResourceTypeId", "ELECTRONIC_TEXT");
        dataResource.put("statusId", "CTNT_PUBLISHED");
        dataResource = delegator.createSetNextSeqId(dataResource);
        String dataResourceId = dataResource.getString("dataResourceId");

        // create electronicText
        GenericValue electronicText = delegator.makeValue("ElectronicText");
        electronicText.put("dataResourceId", dataResourceId);

        electronicText.put("textData", textData);
        electronicText = delegator.create(electronicText);

        // create content
        GenericValue content = delegator.makeValue("Content");
        if (UtilValidate.isNotEmpty(localeString)) content.put("localeString", localeString);
        content.put("dataResourceId", dataResourceId);
        content.put("contentTypeId", "DOCUMENT");
        content.put("description", "Alternative URL");
        content = delegator.createSetNextSeqId(content);
        
        return content;
    }
    
    private static void updateAltUrlSimpleTextContent(Delegator delegator, LocalDispatcher dispatcher, GenericValue content, String localeString, String textData) throws GenericEntityException {
        updateAltUrlSimpleTextContent(delegator, dispatcher, content, textData);
        content.put("localeString", localeString);
        content.store();
    }
    
    private static void updateAltUrlSimpleTextContent(Delegator delegator, LocalDispatcher dispatcher, GenericValue content, String textData) throws GenericEntityException {
        GenericValue elecText = getContentElectronicText(delegator, dispatcher, content);
        elecText.put("textData", textData);
        elecText.store();
    }
    
    private static GenericValue getContentElectronicText(Delegator delegator, LocalDispatcher dispatcher, GenericValue content) throws GenericEntityException {
        GenericValue elecText = EntityQuery.use(delegator).from("ElectronicText")
                .where("dataResourceId", content.getString("dataResourceId"))
                .cache(false).queryOne();
        if (elecText == null) {
            throw new GenericEntityException("Simple text content '" + content.getString("contentId") + "' has no ElectronicText");
        }
        return elecText;
    }
    
    /**
     * Re-generates alternative urls for store/website based on ruleset outlined in SeoConfig.xml.
     */
    public static Map<String, Object> generateWebsiteAlternativeUrls(DispatchContext dctx, Map<String, ? extends Object> context) {
        //Delegator delegator = dctx.getDelegator();
        //LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
       
        Collection<String> typeGenerate = UtilGenerics.checkCollection(context.get("typeGenerate"));
        if (typeGenerate == null) typeGenerate = Collections.emptyList();
        boolean doAll = typeGenerate.contains("all");
        boolean doProduct = doAll || typeGenerate.contains("product");
        boolean doCategory = doAll || typeGenerate.contains("category");
        // NOTE: we must enable search for child product in this case, because they are not automatically
        // covered by ProductCategoryMember in the iteration
        final boolean doChildProducts = true;
        
        String webSiteId = (String) context.get("webSiteId");
        String productStoreId = (String) context.get("productStoreId");
        String prodCatalogId = (String) context.get("prodCatalogId");
        if ("all".equals(prodCatalogId)) prodCatalogId = null; // legacy compat
        Collection<String> prodCatalogIdList = UtilGenerics.checkCollection(context.get("prodCatalogIdList"));
        
        boolean useCache = Boolean.TRUE.equals(context.get("useCache")); // FALSE default
        boolean preventDuplicates = !Boolean.FALSE.equals(context.get("preventDuplicates"));
        
        SeoCatalogUrlGenerator traverser;
        try {
            GenTraversalConfig travConfig = (GenTraversalConfig) new GenTraversalConfig()
                    .setServCtxOpts(context)
                    .setDoChildProducts(doChildProducts)
                    .setUseCache(useCache).setDoCategory(doCategory).setDoProduct(doProduct)
                    .setPreventDupAll(preventDuplicates);
            traverser = new SeoCatalogUrlGenerator(dctx.getDelegator(), dctx.getDispatcher(), travConfig);
        } catch (Exception e) {
            String message = "Error preparing to generate alternative links: " + e.getMessage();
            Debug.logError(e, logPrefix+"generateWebsiteAlternativeUrls: "+message, module);
            return ServiceUtil.returnError(message);
        }
        
        try {
            List<GenericValue> prodCatalogList = traverser.getTargetCatalogList(prodCatalogId, prodCatalogIdList, 
                    productStoreId, webSiteId, false);
            traverser.traverseCatalogsDepthFirst(prodCatalogList);
        } catch(Exception e) {
            String message = "Error while generating alternative links: " + e.getMessage();
            Debug.logError(e, logPrefix+"generateWebsiteAlternativeUrls: "+message, module);
            return ServiceUtil.returnError(message);
        }
        
        String resultMsg = "Alternative URL generation for website '" + webSiteId + "' finished. " + traverser.getStats().toMsg(locale);
        Debug.logInfo(logPrefix+"generateWebsiteAlternativeUrls: " + resultMsg, module);
        return traverser.getStats().toServiceResultSuccessFailure(resultMsg);
    }
    
    /**
     * Re-generates alternative urls for all stores/websites based on ruleset outlined in SeoConfig.xml.
     */
    public static Map<String, Object> generateAllAlternativeUrls(DispatchContext dctx, Map<String, ? extends Object> context) {
        //Delegator delegator = dctx.getDelegator();
        //LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = (Locale) context.get("locale");
        
        Collection<String> typeGenerate = UtilGenerics.checkCollection(context.get("typeGenerate"));
        if (typeGenerate == null) typeGenerate = Collections.emptyList();
        boolean doAll = typeGenerate.contains("all");
        boolean doProduct = doAll || typeGenerate.contains("product");
        boolean doCategory = doAll || typeGenerate.contains("category");
        // NOTE: don't search for child product in this case, because using massive all-products query
        final boolean doChildProducts = false;
        
        boolean useCache = Boolean.TRUE.equals(context.get("useCache")); // FALSE default
        
        //boolean replaceExisting = !Boolean.FALSE.equals(context.get("replaceExisting")); // makeValidContext transfers

        SeoCatalogUrlGenerator traverser;
        try {
            GenTraversalConfig travConfig = (GenTraversalConfig) new GenTraversalConfig()
                    .setServCtxOpts(context)
                    .setDoChildProducts(doChildProducts)
                    .setUseCache(useCache).setDoCategory(doCategory).setDoProduct(doProduct);
            traverser = new SeoCatalogUrlGenerator(dctx.getDelegator(), dctx.getDispatcher(), travConfig);
        } catch (Exception e) {
            String message = "Error preparing to generate alternative links: " + e.getMessage();
            Debug.logError(e, logPrefix+"generateAllAlternativeUrls: "+message, module);
            return ServiceUtil.returnError(message);
        }

        try {
            traverser.traverseAllInSystem();
        } catch (Exception e) {
            String message = "Error while generating alternative links: " + e.getMessage();
            Debug.logError(e, logPrefix+"generateAllAlternativeUrls: " + message, module);
            return ServiceUtil.returnError(message);
        }
        
        // TODO?: FUTURE
        //if (doContent) {
        //}
        
        String resultMsg = "System-wide alternative URL generation finished. " + traverser.getStats().toMsg(locale);
        Debug.logInfo(logPrefix+"generateAllAlternativeUrls: " + resultMsg, module);
        return traverser.getStats().toServiceResultSuccessFailure(resultMsg);
    }
    
    /**
     * Exports alternative urls for given website.
     */
    public static Map<String, Object> exportWebsiteAlternativeUrlsEntityXml(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = (Locale) context.get("locale");
       
        Collection<String> typeExport = UtilGenerics.checkCollection(context.get("typeExport"));
        if (typeExport == null) typeExport = Collections.emptyList();
        boolean doAll = typeExport.contains("all");
        boolean doProduct = doAll || typeExport.contains("product");
        boolean doCategory = doAll || typeExport.contains("category");
        // NOTE: we must enable search for child product in this case, because they are not automatically
        // covered by ProductCategoryMember in the iteration
        final boolean doChildProducts = true;
        
        String webSiteId = (String) context.get("webSiteId");
        String productStoreId = (String) context.get("productStoreId");
        String prodCatalogId = (String) context.get("prodCatalogId");
        if ("all".equals(prodCatalogId)) prodCatalogId = null; // legacy compat
        Collection<String> prodCatalogIdList = UtilGenerics.checkCollection(context.get("prodCatalogIdList"));
        String recordGrouping = (String) context.get("recordGrouping");
        
        boolean useCache = Boolean.TRUE.equals(context.get("useCache")); // FALSE default
        boolean preventDuplicates = !Boolean.FALSE.equals(context.get("preventDuplicates"));
        ExportOutput out = new ExportOutput(context);

        SeoCatalogUrlExporter exporter;
        try {
            ExportTraversalConfig travConfig = (ExportTraversalConfig) new ExportTraversalConfig()
                    .setServCtxOpts(context).setDoChildProducts(doChildProducts).setLinePrefix(out.getLinePrefix())
                    .setRecordGrouping(recordGrouping)
                    .setUseCache(useCache).setDoCategory(doCategory).setDoProduct(doProduct)
                    .setPreventDupAll(preventDuplicates);
            exporter = new SeoCatalogUrlExporter(dctx.getDelegator(), dctx.getDispatcher(), 
                    travConfig, out.getEffWriter());
        } catch (Exception e) {
            String message = "Error preparing to export alternative URLs: " + e.getMessage();
            Debug.logError(e, logPrefix+"exportWebsiteAlternativeUrlsEntityXml: "+message, module);
            return ServiceUtil.returnError(message);
        }
        
        try {
            List<GenericValue> prodCatalogList = exporter.getTargetCatalogList(prodCatalogId, prodCatalogIdList, 
                    productStoreId, webSiteId, false);
            exporter.traverseCatalogsDepthFirst(prodCatalogList);
            out.processOutput(exporter);
        } catch(Exception e) {
            String message = "Error while exporting alternative URLs: " + e.getMessage();
            Debug.logError(e, logPrefix+"exportWebsiteAlternativeUrlsEntityXml: "+message, module);
            return ServiceUtil.returnError(message);
        }
        
        String resultMsg = exporter.getStats().toMsg(locale);
        Map<String, Object> result = exporter.getStats().toServiceResultSuccessFailure(resultMsg);
        out.populateServiceResult(result);
        Debug.logInfo(logPrefix+"exportWebsiteAlternativeUrlsEntityXml: Exported alternative URLs: " + resultMsg, module);        
        return result;
    }
    
    public static Map<String, Object> exportAllAlternativeUrlsEntityXml(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = (Locale) context.get("locale");
        
        Collection<String> typeGenerate = UtilGenerics.checkCollection(context.get("typeExport"));
        if (typeGenerate == null) typeGenerate = Collections.emptyList();
        boolean doAll = typeGenerate.contains("all");
        boolean doProduct = doAll || typeGenerate.contains("product");
        boolean doCategory = doAll || typeGenerate.contains("category");
        // NOTE: don't search for child product in this case, because using massive all-products query
        final boolean doChildProducts = false;
        String recordGrouping = (String) context.get("recordGrouping");
        
        boolean useCache = Boolean.TRUE.equals(context.get("useCache")); // FALSE default
        ExportOutput out = new ExportOutput(context);

        SeoCatalogUrlExporter exporter;
        try {
            ExportTraversalConfig travConfig = (ExportTraversalConfig) new ExportTraversalConfig()
                    .setServCtxOpts(context).setDoChildProducts(doChildProducts).setLinePrefix(out.getLinePrefix())
                    .setRecordGrouping(recordGrouping)
                    .setUseCache(useCache).setDoCategory(doCategory).setDoProduct(doProduct);
            exporter = new SeoCatalogUrlExporter(dctx.getDelegator(), dctx.getDispatcher(), 
                    travConfig, out.getEffWriter());
        } catch (Exception e) {
            String message = "Error preparing to export alternative URLs: " + e.getMessage();
            Debug.logError(e, logPrefix+"exportAllAlternativeUrlsEntityXml: "+message, module);
            return ServiceUtil.returnError(message);
        }

        try {
            exporter.traverseAllInSystem();
            out.processOutput(exporter);
        } catch (Exception e) {
            String message = "Error while exporting alternative URLs: " + e.getMessage();
            Debug.logError(e, logPrefix+"exportAllAlternativeUrlsEntityXml: " + message, module);
            return ServiceUtil.returnError(message);
        }
        
        // TODO?: FUTURE
        //if (doContent) {
        //}
        
        String resultMsg = exporter.getStats().toMsg(locale);
        Map<String, Object> result = exporter.getStats().toServiceResultSuccessFailure(resultMsg);
        out.populateServiceResult(result);
        Debug.logInfo(logPrefix+"exportAllAlternativeUrlsEntityXml: exported alternative URLs: " + resultMsg, module);
        return result;
    }
    
    private static class ExportOutput {
        // in
        Writer origWriter;
        Writer effWriter;
        boolean asString;
        boolean outFlush;
        String linePrefix;
        
        // out
        String outString = null;
        
        public ExportOutput(Map<String, ?> context) {
            origWriter = (Writer) context.get("outWriter");
            effWriter = origWriter;
            asString = Boolean.TRUE.equals(context.get("asString"));
            if (effWriter == null || (asString && !(effWriter instanceof StringWriter))) {
                effWriter = new StringWriter();
            }
            outFlush = !Boolean.FALSE.equals(context.get("outFlush"));
            linePrefix = (String) context.get("linePrefix");
        }
        
        public Writer getEffWriter() {
            return effWriter;
        }
       
        public String getLinePrefix() {
            return linePrefix;
        }

        public boolean isIntermediateWriter() {
            return (origWriter != effWriter);
        }
        
        public void processOutput(SeoCatalogUrlExporter exporter) throws GeneralException {
            flushExporter(exporter);
            
            if (isIntermediateWriter()) {
                StringWriter sw = (StringWriter) effWriter;
                outString = sw.toString();
                
                if (origWriter != null) {
                    try {
                        origWriter.write(outString);
                        origWriter.flush();
                    } catch (IOException e) {
                        throw new GeneralException("Error writing intermediate StringWriter result"
                                + " back to original writer following data export", e);
                    }
                }
            }
        }
        
        /**
         * Flushes the exporter if needed.
         * Generally the exporter must be flushed because it may have wrapped the effWriter
         * in a PrintWriter. We can only avoid flush if origWriter was a PrintWriter because then service caller is responsible.
         * Option conflict is possible.
         */
        private void flushExporter(SeoCatalogUrlExporter exporter) throws GeneralException {
            boolean flushWriter = false;
            if (isIntermediateWriter()) {
                // no risk flushing the StringWriter
                flushWriter = true;
            } else {
                if (origWriter instanceof PrintWriter) {
                    // here, we can respect the flush flag, because the caller is responsible
                    // for the entire PrintWriter wrapper flushing (not just its wrapped instance)
                    if (outFlush) {
                        flushWriter = true;
                    }
                } else {
                    // here, we are forced to flush, but caller may not like...
                    if (!outFlush) {
                        Debug.logWarning(logPrefix+"SEO catalog URL export: "
                                + "forced to flush the writer; caller requested no flushing (outFlush=false), "
                                + "but this flag can only be honored if caller passed a PrintWriter"
                                + " (caller passed a: " + origWriter.getClass().getName() 
                                + ")", module);
                    }
                    flushWriter = true;
                }
            }
            exporter.flush(flushWriter);
        }

        public void populateServiceResult(Map<String, Object> result) {
            if (origWriter != null) {
                result.put("outWriter", origWriter);
            } else {
                result.put("outWriter", effWriter);
            }
  
            if (asString) {
                result.put("outString", outString);
            }
        }
    }
    
    public static Map<String, Object> exportWebsiteAlternativeUrlsEntityXmlFile(DispatchContext dctx, Map<String, ? extends Object> context) {
        String outFile = (String) context.get("outFile");
        String templateType = (String) context.get("templateType");
        String dataBeginMarker = (String) context.get("dataBeginMarker");
        String templateFile = (String) context.get("templateFile");
        
        boolean commentDelimTmpl = "comment-delimited".equals(templateType);
        if (commentDelimTmpl && dataBeginMarker == null) dataBeginMarker = "<!--DATA-BEGIN-->";
        
        PrintWriter outWriter = null;
        try {
            String header = null;
            if (commentDelimTmpl) {                
                StringBuilder headerSb = ExportDataFileConfig.readCommentDelimitedTemplateHeader(templateFile, dataBeginMarker);
                if (headerSb == null) {
                    Debug.logWarning(logPrefix+"Data export: could not find the data-begin markup (" + dataBeginMarker 
                        + ") in the template file '" + templateFile + "'; there will be no header in the output file (" + outFile + ")", module);
                } else {
                    header = headerSb.toString();
                }
            }

            // will use a PrintWriter right away to avoid possible issues
            outWriter = new PrintWriter(ExportDataFileConfig.getOutFileWriter(outFile));
            
            // print header
            outWriter.print((header != null) ? header : ExportDataFileConfig.getDefaultHeader());
            
            // print body
            Map<String, Object> servCtx = dctx.makeValidContext("exportWebsiteAlternativeUrlsEntityXml", ModelService.IN_PARAM, context);
            servCtx.put("outWriter", outWriter);
            Map<String, Object> servResult = dctx.getDispatcher().runSync("exportWebsiteAlternativeUrlsEntityXml", servCtx);
            if (ServiceUtil.isError(servResult)) {
                String message = "Error exporting alternative URLs: " + ServiceUtil.getErrorMessage(servResult);
                Debug.logError(logPrefix+"exportWebsiteAlternativeUrlsEntityXmlFile: "+message, module);
                return ServiceUtil.returnError(message);
            }
            
            // print footer and flush
            outWriter.print(ExportDataFileConfig.getDefaultFooter());
            outWriter.flush();
            
            String msg = "Exported alternative URL entity XML for website '" + context.get("webSiteId") + "' to file '" + outFile 
                    + "': " + ServiceUtil.getErrorAndSuccessMessage(servResult);
            Debug.logInfo(logPrefix+"exportWebsiteAlternativeUrlsEntityXmlFile: " + msg, module);
            return (ServiceUtil.isSuccess(servResult)) ? ServiceUtil.returnSuccess(msg) : ServiceUtil.returnFailure(msg);
        } catch (Exception e) {
            String message = "Error exporting alternative URLs: " + e.getMessage();
            Debug.logError(e, logPrefix+"exportWebsiteAlternativeUrlsEntityXmlFile: "+message, module);
            return ServiceUtil.returnError(message);
        } finally {
            if (outWriter != null) {
                try {
                    outWriter.close();
                } catch(Exception e) {
                    Debug.logError(e, module);
                }
            }
        }
    }
    
    public static Map<String, Object> exportAlternativeUrlsEntityXmlFileFromConfig(DispatchContext dctx, Map<String, ? extends Object> context) {
        Collection<String> configNameList = combineNameAndNameList((String) context.get("configName"),
                UtilGenerics.<String>checkCollection(context.get("configNameList")));
        if (UtilValidate.isEmpty(configNameList)) {
            configNameList = ExportDataFileConfig.getAllConfigNames();
            Debug.logInfo(logPrefix+"exportAlternativeUrlsEntityXmlFileFromConfig: no config names specified: running for all configs: " + configNameList, module);
        }
        
        int configSuccess = 0;
        int configFailure = 0;
        Map<String, Object> lastResult = null;
        
        try {
            for(String configName : configNameList) {
                Map<String, Object> config = ExportDataFileConfig.getConfigByName(configName);
                if (config == null) {
                    return ServiceUtil.returnError("could not read datafile export config for config name '" + configName + "'");
                }
                try {
                    Map<String, Object> servCtx = dctx.makeValidContext("exportWebsiteAlternativeUrlsEntityXmlFile", ModelService.IN_PARAM, context);
                    Map<String, Object> servConfig = dctx.makeValidContext("exportWebsiteAlternativeUrlsEntityXmlFile", ModelService.IN_PARAM, config);
                    servCtx.putAll(servConfig);
                    Map<String, Object> servResult = dctx.getDispatcher().runSync("exportWebsiteAlternativeUrlsEntityXmlFile", servCtx);
                    lastResult = servResult;
                    if (ServiceUtil.isError(servResult)) {
                        String message = "Error exporting alternative URLs for config '" + configName + "': " + ServiceUtil.getErrorMessage(servResult);
                        Debug.logError(logPrefix+"exportAlternativeUrlsEntityXmlFileFromConfig: "+message, module);
                        return ServiceUtil.returnError(message);
                    } else if (ServiceUtil.isFailure(servResult)) {
                        configFailure++;
                    } else {
                        configSuccess++;
                    }
                } catch(Exception e) {
                    String message = "Error exporting alternative URLs for config '" + configName + "': " + e.getMessage();
                    Debug.logError(e, logPrefix+"exportAlternativeUrlsEntityXmlFileFromConfig: "+message, module);
                    return ServiceUtil.returnError(message);
                }
            }
        } catch(Exception e) {
            String message = "Error exporting alternative URLs: " + e.getMessage();
            Debug.logError(e, logPrefix+"exportAlternativeUrlsEntityXmlFileFromConfig: "+message, module);
            return ServiceUtil.returnError(message);
        }
        
        // special case, helps debug
        if (configNameList.size() == 1) {
            String msg = "Export config '" + configNameList.iterator().next() 
                    + "' " + ((configFailure > 0) ? "failed" : "succeeded") + ": " + ServiceUtil.getErrorAndSuccessMessage(lastResult);
            return (configFailure > 0) ? ServiceUtil.returnFailure(msg) : ServiceUtil.returnSuccess(msg);
        } else {
            String msg = "Export configs succeeded: " + configSuccess + "; failed: " + configFailure;
            return (configFailure > 0) ? ServiceUtil.returnFailure(msg) : ServiceUtil.returnSuccess(msg);
        }
    }
    
    static Collection<String> combineNameAndNameList(String name, Collection<String> nameList) {
        if (UtilValidate.isEmpty(name)) return nameList;
        
        if (UtilValidate.isEmpty(nameList)) {
            return UtilMisc.toList(name);
        } else {
            Collection<String> newNameList = new ArrayList<>(nameList.size() + 1);
            newNameList.add(name);
            newNameList.addAll(nameList);
            return newNameList;
        }
    }
    
}