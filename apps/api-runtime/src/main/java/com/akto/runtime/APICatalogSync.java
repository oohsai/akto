package com.akto.runtime;

import com.akto.dao.*;
import com.akto.dao.context.Context;
import com.akto.dto.*;
import com.akto.dto.HttpResponseParams.Source;
import com.akto.dto.traffic.Key;
import com.akto.dto.traffic.SampleData;
import com.akto.dto.traffic.TrafficInfo;
import com.akto.dto.type.*;
import com.akto.dto.type.SingleTypeInfo.SubType;
import com.akto.dto.type.SingleTypeInfo.SuperType;
import com.akto.dto.type.URLMethods.Method;
import com.akto.log.LoggerMaker;
import com.akto.log.LoggerMaker.LogDb;
import com.akto.parsers.HttpCallParser;
import com.akto.runtime.merge.MergeOnHostOnly;
import com.akto.task.Cluster;
import com.akto.types.CappedSet;
import com.akto.utils.RedactSampleData;
import com.mongodb.BasicDBObject;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.*;
import org.apache.commons.lang3.math.NumberUtils;
import org.bson.conversions.Bson;
import org.bson.json.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.akto.dto.type.KeyTypes.patternToSubType;

public class APICatalogSync {
    
    public int thresh;
    public String userIdentifier;
    private static final Logger logger = LoggerFactory.getLogger(APICatalogSync.class);
    private static final LoggerMaker loggerMaker = new LoggerMaker(APICatalogSync.class);
    public Map<Integer, APICatalog> dbState;
    public Map<Integer, APICatalog> delta;
    public Map<SensitiveParamInfo, Boolean> sensitiveParamInfoBooleanMap;
    public static boolean mergeAsyncOutside = false;

    public APICatalogSync(String userIdentifier,int thresh) {
        this.thresh = thresh;
        this.userIdentifier = userIdentifier;
        this.dbState = new HashMap<>();
        this.delta = new HashMap<>();
        this.sensitiveParamInfoBooleanMap = new HashMap<>();
        try {
            String instanceType =  System.getenv("AKTO_INSTANCE_TYPE");
            if (instanceType != null && "RUNTIME".equalsIgnoreCase(instanceType)) {
                mergeAsyncOutside = AccountSettingsDao.instance.findOne(AccountSettingsDao.generateFilter()).getMergeAsyncOutside();
            }
        } catch (Exception e) {

        }

    }

    public static final int STRING_MERGING_THRESHOLD = 10;

    public void processResponse(RequestTemplate requestTemplate, Collection<HttpResponseParams> responses, List<SingleTypeInfo> deletedInfo) {
        Iterator<HttpResponseParams> iter = responses.iterator();
        while(iter.hasNext()) {
            try {
                processResponse(requestTemplate, iter.next(), deletedInfo);
            } catch (Exception e) {
                e.printStackTrace();
                loggerMaker.errorAndAddToDb("processResponse: " + e.getMessage(), LogDb.RUNTIME);
            }
        }
    }

    public void processResponse(RequestTemplate requestTemplate, HttpResponseParams responseParams, List<SingleTypeInfo> deletedInfo) {
        HttpRequestParams requestParams = responseParams.getRequestParams();
        String urlWithParams = requestParams.getURL();
        String methodStr = requestParams.getMethod();
        URLStatic baseURL = URLAggregator.getBaseURL(urlWithParams, methodStr);
        responseParams.requestParams.url = baseURL.getUrl();
        int statusCode = responseParams.getStatusCode();
        Method method = Method.fromString(methodStr);
        String userId = extractUserId(responseParams, userIdentifier);

        if (!responseParams.getIsPending()) {
            requestTemplate.processTraffic(responseParams.getTime());
        }
        if (HttpResponseParams.validHttpResponseCode(statusCode)) {
            String reqPayload = requestParams.getPayload();

            if (reqPayload == null || reqPayload.isEmpty()) {
                reqPayload = "{}";
            }

            requestTemplate.processHeaders(requestParams.getHeaders(), baseURL.getUrl(), methodStr, -1, userId, requestParams.getApiCollectionId(), responseParams.getOrig(), sensitiveParamInfoBooleanMap);
            BasicDBObject payload = RequestTemplate.parseRequestPayload(requestParams, urlWithParams);
            if (payload != null) {
                deletedInfo.addAll(requestTemplate.process2(payload, baseURL.getUrl(), methodStr, -1, userId, requestParams.getApiCollectionId(), responseParams.getOrig(), sensitiveParamInfoBooleanMap));
            }
            requestTemplate.recordMessage(responseParams.getOrig());
        }

        Map<Integer, RequestTemplate> responseTemplates = requestTemplate.getResponseTemplates();
        
        RequestTemplate responseTemplate = responseTemplates.get(statusCode);
        if (responseTemplate == null) {
            responseTemplate = new RequestTemplate(new HashMap<>(), null, new HashMap<>(), new TrafficRecorder(new HashMap<>()));
            responseTemplates.put(statusCode, responseTemplate);
        }

        try {
            String respPayload = responseParams.getPayload();

            if (respPayload == null || respPayload.isEmpty()) {
                respPayload = "{}";
            }

            if(respPayload.startsWith("[")) {
                respPayload = "{\"json\": "+respPayload+"}";
            }


            BasicDBObject payload;
            try {
                payload = BasicDBObject.parse(respPayload);
            } catch (Exception e) {
                payload = BasicDBObject.parse("{}");
            }

            deletedInfo.addAll(responseTemplate.process2(payload, baseURL.getUrl(), methodStr, statusCode, userId, requestParams.getApiCollectionId(), responseParams.getOrig(), sensitiveParamInfoBooleanMap));
            responseTemplate.processHeaders(responseParams.getHeaders(), baseURL.getUrl(), method.name(), statusCode, userId, requestParams.getApiCollectionId(), responseParams.getOrig(), sensitiveParamInfoBooleanMap);
            if (!responseParams.getIsPending()) {
                responseTemplate.processTraffic(responseParams.getTime());
            }

        } catch (JsonParseException e) {
            loggerMaker.errorAndAddToDb("Failed to parse json payload " + e.getMessage(), LogDb.RUNTIME);
        }
    }

    public static String extractUserId(HttpResponseParams responseParams, String userIdentifier) {
        List<String> token = responseParams.getRequestParams().getHeaders().get(userIdentifier);
        if (token == null || token.size() == 0) {
            return "HC";
        } else {
            return token.get(0);
        }
    }

    int countUsers(Set<HttpResponseParams> responseParamsList) {
        Set<String> users = new HashSet<>();
        for(HttpResponseParams responseParams: responseParamsList) {
            users.add(extractUserId(responseParams, userIdentifier));
        }

        return users.size();
    }

    public void computeDelta(URLAggregator origAggregator, boolean triggerTemplateGeneration, int apiCollectionId) {
        long start = System.currentTimeMillis();

        APICatalog deltaCatalog = this.delta.get(apiCollectionId);
        if (deltaCatalog == null) {
            deltaCatalog = new APICatalog(apiCollectionId, new HashMap<>(), new HashMap<>());
            this.delta.put(apiCollectionId, deltaCatalog);
        } 

        APICatalog dbCatalog = this.dbState.get(apiCollectionId);
        if (dbCatalog == null) {
            dbCatalog = new APICatalog(apiCollectionId, new HashMap<>(), new HashMap<>());
            this.dbState.put(apiCollectionId, dbCatalog);
        } 

        URLAggregator aggregator = new URLAggregator(origAggregator.urls);
        origAggregator.urls = new ConcurrentHashMap<>();

        start = System.currentTimeMillis();
        processKnownStaticURLs(aggregator, deltaCatalog, dbCatalog);

        start = System.currentTimeMillis();
        Map<URLStatic, RequestTemplate> pendingRequests = createRequestTemplates(aggregator);

        start = System.currentTimeMillis();
        tryWithKnownURLTemplates(pendingRequests, deltaCatalog, dbCatalog, apiCollectionId );

        if (!mergeAsyncOutside) {
            start = System.currentTimeMillis();
            tryMergingWithKnownStrictURLs(pendingRequests, dbCatalog, deltaCatalog);
        } else {
            for (URLStatic pending: pendingRequests.keySet()) {
                RequestTemplate pendingTemplate = pendingRequests.get(pending);
                RequestTemplate rt = deltaCatalog.getStrictURLToMethods().get(pending);
                if (rt != null) {
                    rt.mergeFrom(pendingTemplate);
                } else {
                    deltaCatalog.getStrictURLToMethods().put(pending, pendingTemplate);
                }
            }
        }
    }


    public static ApiMergerResult tryMergeURLsInCollection(int apiCollectionId, Boolean urlRegexMatchingEnabled) {
        ApiCollection apiCollection = ApiCollectionsDao.instance.getMeta(apiCollectionId);

        Bson filterQ = null;
        if (apiCollection != null && apiCollection.getHostName() == null) {
            filterQ = Filters.eq("apiCollectionId", apiCollectionId);
        } else {
            filterQ = Filters.and(
                Filters.eq("apiCollectionId", apiCollectionId),
                Filters.or(Filters.eq("isHeader", false), Filters.eq("param", "host"))
            );            
        }

        int offset = 0;
        int limit = 1_000_000;

        List<SingleTypeInfo> singleTypeInfos = new ArrayList<>();
        ApiMergerResult finalResult = new ApiMergerResult(new HashMap<>());
        do {
            singleTypeInfos = SingleTypeInfoDao.instance.findAll(filterQ, offset, limit, null, Projections.exclude("values"));


            Map<String, Set<String>> staticUrlToSti = new HashMap<>();
            Set<String> templateUrlSet = new HashSet<>();
            List<String> templateUrls = new ArrayList<>();
            for(SingleTypeInfo sti: singleTypeInfos) {
                String key = sti.getMethod() + " " + sti.getUrl();
                if (key.contains("INTEGER") || key.contains("STRING") || key.contains("UUID")) {
                    templateUrlSet.add(key);
                    continue;
                };

                if (sti.getIsUrlParam()) continue;
                if (sti.getIsHeader()) {
                    staticUrlToSti.putIfAbsent(key, new HashSet<>());
                    continue;
                }


                Set<String> set = staticUrlToSti.get(key);
                if (set == null) {
                    set = new HashSet<>();
                    staticUrlToSti.put(key, set);
                }

                set.add(sti.getResponseCode() + " " + sti.getParam());
            }

            for (String s: templateUrlSet) {
                templateUrls.add(s);
            }

            for(String staticURL: staticUrlToSti.keySet()) {
                Method staticMethod = Method.fromString(staticURL.split(" ")[0]);
                String staticEndpoint = staticURL.split(" ")[1];

                for (String templateURL: templateUrls) {
                    Method templateMethod = Method.fromString(templateURL.split(" ")[0]);
                    String templateEndpoint = templateURL.split(" ")[1];

                    URLTemplate urlTemplate = createUrlTemplate(templateEndpoint, templateMethod);
                    if (urlTemplate.match(staticEndpoint, staticMethod)) {
                        finalResult.deleteStaticUrls.add(staticURL);
                        break;
                    }
                }
            }

            Map<Integer, Map<String, Set<String>>> sizeToUrlToSti = groupByTokenSize(staticUrlToSti);

            sizeToUrlToSti.remove(1);
            sizeToUrlToSti.remove(0);


            for(int size: sizeToUrlToSti.keySet()) {
                ApiMergerResult result = tryMergingWithKnownStrictURLs(sizeToUrlToSti.get(size), urlRegexMatchingEnabled);    
                finalResult.templateToStaticURLs.putAll(result.templateToStaticURLs);
            }

            offset += limit;
        } while (!singleTypeInfos.isEmpty());

        return finalResult;
    }

    private static Map<Integer, Map<String, Set<String>>> groupByTokenSize(Map<String, Set<String>> catalog) {
        Map<Integer, Map<String, Set<String>>> sizeToURL = new HashMap<>();
        for(String rawURLPlusMethod: catalog.keySet()) {
            String[] rawUrlPlusMethodSplit = rawURLPlusMethod.split(" ");
            String rawURL = rawUrlPlusMethodSplit.length > 1 ? rawUrlPlusMethodSplit[1] : rawUrlPlusMethodSplit[0];
            Set<String> reqTemplate = catalog.get(rawURLPlusMethod);
            String url = APICatalogSync.trim(rawURL);
            String[] tokens = url.split("/");
            Map<String, Set<String>> urlSet = sizeToURL.get(tokens.length);
            urlSet = sizeToURL.get(tokens.length);
            if (urlSet == null) {
                urlSet = new HashMap<>();
                sizeToURL.put(tokens.length, urlSet);
            }

            urlSet.put(rawURLPlusMethod, reqTemplate);
        }

        return sizeToURL;
    }

    static class ApiMergerResult {
        public Set<String> deleteStaticUrls = new HashSet<>();
        Map<URLTemplate, Set<String>> templateToStaticURLs = new HashMap<>();

        public ApiMergerResult(Map<URLTemplate, Set<String>> templateToSti) {
            this.templateToStaticURLs = templateToSti;
        }

        public String toString() {
            String ret = ("templateToSti======================================================: \n");
            for(URLTemplate urlTemplate: templateToStaticURLs.keySet()) {
                ret += (urlTemplate.getTemplateString()) + "\n";
                for(String str: templateToStaticURLs.get(urlTemplate)) {
                    ret += ("\t " + str + "\n");
                }
            }

            return ret;
        }
    }

    private static ApiMergerResult tryMergingWithKnownStrictURLs(
        Map<String, Set<String>> pendingRequests, Boolean urlRegexMatchingEnabled
    ) {
        Map<URLTemplate, Set<String>> templateToStaticURLs = new HashMap<>();

        Iterator<Map.Entry<String, Set<String>>> iterator = pendingRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Set<String>> entry = iterator.next();
            iterator.remove();

            String newUrl = entry.getKey();
            Set<String> newTemplate = entry.getValue();
            Method newMethod = Method.fromString(newUrl.split(" ")[0]);
            String newEndpoint = newUrl.split(" ")[1];

            boolean matchedInDeltaTemplate = false;
            for(URLTemplate urlTemplate: templateToStaticURLs.keySet()){
                if (urlTemplate.match(newEndpoint, newMethod)) {
                    matchedInDeltaTemplate = true;
                    templateToStaticURLs.get(urlTemplate).add(newUrl);
                    break;
                }
            }

            if (matchedInDeltaTemplate) {
                continue;
            }

            int countSimilarURLs = 0;
            Map<URLTemplate, Map<String, Set<String>>> potentialMerges = new HashMap<>();
            for(String aUrl: pendingRequests.keySet()) {
                Set<String> aTemplate = pendingRequests.get(aUrl);
                Method aMethod = Method.fromString(aUrl.split(" ")[0]);
                String aEndpoint = aUrl.split(" ")[1];
                URLStatic aStatic = new URLStatic(aEndpoint, aMethod);
                URLStatic newStatic = new URLStatic(newEndpoint, newMethod);
                URLTemplate mergedTemplate = APICatalogSync.tryMergeUrls(aStatic, newStatic);
                if (mergedTemplate == null) {
                    continue;
                }

                if (APICatalogSync.areBothMatchingUrls(newStatic,aStatic,mergedTemplate, urlRegexMatchingEnabled) || APICatalogSync.areBothUuidUrls(newStatic,aStatic,mergedTemplate) || RequestTemplate.compareKeys(aTemplate, newTemplate, mergedTemplate)) {
                    Map<String, Set<String>> similarTemplates = potentialMerges.get(mergedTemplate);
                    if (similarTemplates == null) {
                        similarTemplates = new HashMap<>();
                        potentialMerges.put(mergedTemplate, similarTemplates);
                    } 
                    similarTemplates.put(aUrl, aTemplate);

                    if (!RequestTemplate.isMergedOnStr(mergedTemplate) || APICatalogSync.areBothUuidUrls(newStatic,aStatic,mergedTemplate) || APICatalogSync.areBothMatchingUrls(newStatic,aStatic,mergedTemplate, urlRegexMatchingEnabled)) {
                        countSimilarURLs = APICatalogSync.STRING_MERGING_THRESHOLD;
                    }

                    countSimilarURLs++;
                }
            }

            if (countSimilarURLs >= APICatalogSync.STRING_MERGING_THRESHOLD) {
                URLTemplate mergedTemplate = potentialMerges.keySet().iterator().next();
                Set<String> matchedStaticURLs = templateToStaticURLs.get(mergedTemplate);

                if (matchedStaticURLs == null) {
                    matchedStaticURLs = new HashSet<>();
                    templateToStaticURLs.put(mergedTemplate, matchedStaticURLs);
                }

                matchedStaticURLs.add(newUrl);

                for (Map.Entry<String, Set<String>> rt: potentialMerges.getOrDefault(mergedTemplate, new HashMap<>()).entrySet()) {
                    matchedStaticURLs.add(rt.getKey());
                }
            }
        }

        return new ApiMergerResult(templateToStaticURLs);
    }

    private void tryMergingWithKnownStrictURLs(
        Map<URLStatic, RequestTemplate> pendingRequests,
        APICatalog dbCatalog,
        APICatalog deltaCatalog
    ) {
        Iterator<Map.Entry<URLStatic, RequestTemplate>> iterator = pendingRequests.entrySet().iterator();
        Map<Integer, Map<URLStatic, RequestTemplate>> dbSizeToUrlToTemplate = groupByTokenSize(dbCatalog);
        Map<URLStatic, RequestTemplate> deltaTemplates = deltaCatalog.getStrictURLToMethods();
        while (iterator.hasNext()) {
            Map.Entry<URLStatic, RequestTemplate> entry = iterator.next();
            URLStatic newUrl = entry.getKey();
            RequestTemplate newTemplate = entry.getValue();
            String[] tokens = tokenize(newUrl.getUrl());

            if (tokens.length == 1) {
                RequestTemplate rt = deltaTemplates.get(newUrl);
                if (rt != null) {
                    rt.mergeFrom(newTemplate);
                } else {
                    deltaTemplates.put(newUrl, newTemplate);
                }
                iterator.remove();
                continue;
            }

            boolean matchedInDeltaTemplate = false;
            for(URLTemplate urlTemplate: deltaCatalog.getTemplateURLToMethods().keySet()){
                RequestTemplate deltaTemplate = deltaCatalog.getTemplateURLToMethods().get(urlTemplate);
                if (urlTemplate.match(newUrl)) {
                    matchedInDeltaTemplate = true;
                    deltaTemplate.mergeFrom(newTemplate);
                    break;
                }
            }

            if (matchedInDeltaTemplate) {
                iterator.remove();
                continue;
            }

            Map<URLStatic, RequestTemplate> dbTemplates = dbSizeToUrlToTemplate.get(tokens.length);
            if (dbTemplates != null && dbTemplates.size() > 0) {
                boolean newUrlMatchedInDb = false;
                int countSimilarURLs = 0;
                Map<URLTemplate, Set<RequestTemplate>> potentialMerges = new HashMap<>();
                for(URLStatic dbUrl: dbTemplates.keySet()) {
                    RequestTemplate dbTemplate = dbTemplates.get(dbUrl);
                    URLTemplate mergedTemplate = tryMergeUrls(dbUrl, newUrl);
                    if (mergedTemplate == null) {
                        continue;
                    }

                    if (areBothUuidUrls(newUrl,dbUrl,mergedTemplate) || dbTemplate.compare(newTemplate, mergedTemplate)) {
                        Set<RequestTemplate> similarTemplates = potentialMerges.get(mergedTemplate);
                        if (similarTemplates == null) {
                            similarTemplates = new HashSet<>();
                            potentialMerges.put(mergedTemplate, similarTemplates);
                        } 
                        similarTemplates.add(dbTemplate);
                        countSimilarURLs++;
                     }
                }
                     
                if (countSimilarURLs >= STRING_MERGING_THRESHOLD) {
                    URLTemplate mergedTemplate = potentialMerges.keySet().iterator().next();
                    RequestTemplate alreadyInDelta = deltaCatalog.getTemplateURLToMethods().get(mergedTemplate);
                    RequestTemplate dbTemplate = potentialMerges.get(mergedTemplate).iterator().next();

                    if (alreadyInDelta != null) {
                        alreadyInDelta.mergeFrom(newTemplate);
                    } else {
                        RequestTemplate dbCopy = dbTemplate.copy();
                        dbCopy.mergeFrom(newTemplate);    
                        deltaCatalog.getTemplateURLToMethods().put(mergedTemplate, dbCopy);
                    }

                    alreadyInDelta = deltaCatalog.getTemplateURLToMethods().get(mergedTemplate);

                    for (RequestTemplate rt: potentialMerges.getOrDefault(mergedTemplate, new HashSet<>())) {
                        alreadyInDelta.mergeFrom(rt);
                        deltaCatalog.getDeletedInfo().addAll(rt.getAllTypeInfo());
                    }
                    deltaCatalog.getDeletedInfo().addAll(dbTemplate.getAllTypeInfo());
                    
                    newUrlMatchedInDb = true;
                }

                if (newUrlMatchedInDb) {
                    iterator.remove();
                    continue;
                }
            }

            boolean newUrlMatchedInDelta = false;

            for (URLStatic deltaUrl: deltaCatalog.getStrictURLToMethods().keySet()) {
                RequestTemplate deltaTemplate = deltaTemplates.get(deltaUrl);
                URLTemplate mergedTemplate = tryMergeUrls(deltaUrl, newUrl);
                if (mergedTemplate == null || (RequestTemplate.isMergedOnStr(mergedTemplate) && !areBothUuidUrls(newUrl,deltaUrl,mergedTemplate))) {
                    continue;
                }

                newUrlMatchedInDelta = true;
                deltaCatalog.getDeletedInfo().addAll(deltaTemplate.getAllTypeInfo());
                RequestTemplate alreadyInDelta = deltaCatalog.getTemplateURLToMethods().get(mergedTemplate);

                if (alreadyInDelta != null) {
                    alreadyInDelta.mergeFrom(newTemplate);
                } else {
                    RequestTemplate deltaCopy = deltaTemplate.copy();
                    deltaCopy.mergeFrom(newTemplate);    
                    deltaCatalog.getTemplateURLToMethods().put(mergedTemplate, deltaCopy);
                }
                
                deltaCatalog.getStrictURLToMethods().remove(deltaUrl);
                break;
            }
            
            if (newUrlMatchedInDelta) {
                iterator.remove();
                continue;
            }

            RequestTemplate rt = deltaTemplates.get(newUrl);
            if (rt != null) {
                rt.mergeFrom(newTemplate);
            } else {
                deltaTemplates.put(newUrl, newTemplate);
            }
            iterator.remove();
        }
    }

    public static boolean areBothUuidUrls(URLStatic newUrl, URLStatic deltaUrl, URLTemplate mergedTemplate) {
        Pattern pattern = patternToSubType.get(SingleTypeInfo.UUID);

        String[] n = tokenize(newUrl.getUrl());
        String[] o = tokenize(deltaUrl.getUrl());
        SuperType[] b = mergedTemplate.getTypes();
        for (int idx =0 ; idx < b.length; idx++) {
            SuperType c = b[idx];
            if (Objects.equals(c, SuperType.STRING) && o.length > idx) {
                String val = n[idx];
                if(!pattern.matcher(val).matches() || !pattern.matcher(o[idx]).matches()) {
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean areBothMatchingUrls(URLStatic newUrl, URLStatic deltaUrl, URLTemplate mergedTemplate, Boolean urlRegexMatchingEnabled) {

        if (!urlRegexMatchingEnabled) {
            return false;
        }

        String[] n = tokenize(newUrl.getUrl());
        String[] o = tokenize(deltaUrl.getUrl());
        SuperType[] b = mergedTemplate.getTypes();
        for (int idx =0 ; idx < b.length; idx++) {
            SuperType c = b[idx];
            if (Objects.equals(c, SuperType.STRING) && o.length > idx) {
                String val = n[idx];
                if(!isAlphanumericString(val) || !isAlphanumericString(o[idx])) {
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean isAlphanumericString(String s) {

        int intCount = 0;
        int charCount = 0;
        if (s.length() < 6) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {

            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                intCount++;
            } else if (Character.isLetter(c)) {
                charCount++;
            }
        }
        return (intCount >= 3 && charCount >= 1);
    }


    public static URLTemplate tryMergeUrls(URLStatic dbUrl, URLStatic newUrl) {
        if (dbUrl.getMethod() != newUrl.getMethod()) {
            return null;
        }
        String[] dbTokens = tokenize(dbUrl.getUrl());
        String[] newTokens = tokenize(newUrl.getUrl());

        if (dbTokens.length != newTokens.length) {
            return null;
        }

        Pattern pattern = patternToSubType.get(SingleTypeInfo.UUID);

        SuperType[] newTypes = new SuperType[newTokens.length];
        int templatizedStrTokens = 0;
        for(int i = 0; i < newTokens.length; i ++) {
            String tempToken = newTokens[i];
            String dbToken = dbTokens[i];

            if (tempToken.equalsIgnoreCase(dbToken)) {
                continue;
            }
            
            if (NumberUtils.isParsable(tempToken) && NumberUtils.isParsable(dbToken)) {
                newTypes[i] = SuperType.INTEGER;
                newTokens[i] = null;
            } else if(pattern.matcher(tempToken).matches() && pattern.matcher(dbToken).matches()){
                newTypes[i] = SuperType.STRING;
                newTokens[i] = null;
            } else {
                newTypes[i] = SuperType.STRING;
                newTokens[i] = null;
                templatizedStrTokens++;
            }
        }

        if (templatizedStrTokens <= 1) {
            return new URLTemplate(newTokens, newTypes, newUrl.getMethod());
        }

        return null;

    }


    public static void mergeUrlsAndSave(int apiCollectionId, Boolean urlRegexMatchingEnabled) {
        ApiMergerResult result = tryMergeURLsInCollection(apiCollectionId, urlRegexMatchingEnabled);
        ArrayList<WriteModel<SingleTypeInfo>> bulkUpdatesForSti = new ArrayList<>();
        ArrayList<WriteModel<SampleData>> bulkUpdatesForSampleData = new ArrayList<>();
        ArrayList<WriteModel<ApiInfo>> bulkUpdatesForApiInfo = new ArrayList<>();

        for (URLTemplate urlTemplate: result.templateToStaticURLs.keySet()) {
            Set<String> matchStaticURLs = result.templateToStaticURLs.get(urlTemplate);

            boolean isFirst = true;
            for (String matchedURL: matchStaticURLs) {
                Method delMethod = Method.fromString(matchedURL.split(" ")[0]);
                String delEndpoint = matchedURL.split(" ")[1];  
                Bson filterQ = Filters.and(
                    Filters.eq("apiCollectionId", apiCollectionId),
                    Filters.eq("method", delMethod.name()),
                    Filters.eq("url", delEndpoint)
                );

                Bson filterQSampleData = Filters.and(
                    Filters.eq("_id.apiCollectionId", apiCollectionId),
                    Filters.eq("_id.method", delMethod.name()),
                    Filters.eq("_id.url", delEndpoint)
                );

                if (isFirst) {

                    String newTemplateUrl = urlTemplate.getTemplateString();
                    for (int i = 0; i < urlTemplate.getTypes().length; i++) {
                        SuperType superType = urlTemplate.getTypes()[i];
                        if (superType == null) continue;

                        SingleTypeInfo.ParamId stiId = new SingleTypeInfo.ParamId(newTemplateUrl, delMethod.name(), -1, false, i+"", SingleTypeInfo.GENERIC, apiCollectionId, true);
                        SubType subType = KeyTypes.findSubType(i, i+"",stiId);
                        stiId.setSubType(subType);
                        SingleTypeInfo sti = new SingleTypeInfo(
                            stiId, new HashSet<>(), new HashSet<>(), 0, Context.now(), 0, CappedSet.create(i+""), 
                            SingleTypeInfo.Domain.ENUM, SingleTypeInfo.ACCEPTED_MIN_VALUE, SingleTypeInfo.ACCEPTED_MAX_VALUE);


                        // SingleTypeInfoDao.instance.insertOne(sti);
                        bulkUpdatesForSti.add(new InsertOneModel<>(sti));
                    }

                    // SingleTypeInfoDao.instance.getMCollection().updateMany(filterQ, Updates.set("url", newTemplateUrl));

                    bulkUpdatesForSti.add(new UpdateManyModel<>(filterQ, Updates.set("url", newTemplateUrl), new UpdateOptions()));


                    SampleData sd = SampleDataDao.instance.findOne(filterQSampleData);
                    if (sd != null) {
                        sd.getId().url = newTemplateUrl;
                        // SampleDataDao.instance.insertOne(sd);
                        bulkUpdatesForSampleData.add(new InsertOneModel<>(sd));
                    }


                    ApiInfo apiInfo = ApiInfoDao.instance.findOne(filterQSampleData);
                    if (apiInfo != null) {
                        apiInfo.getId().url = newTemplateUrl;
                        // ApiInfoDao.instance.insertOne(apiInfo);
                        bulkUpdatesForApiInfo.add(new InsertOneModel<>(apiInfo));
                    }

                    isFirst = false;
                } else {
                    bulkUpdatesForSti.add(new DeleteManyModel<>(filterQ));
                    // SingleTypeInfoDao.instance.deleteAll(filterQ);

                }

                bulkUpdatesForSampleData.add(new DeleteManyModel<>(filterQSampleData));
                bulkUpdatesForApiInfo.add(new DeleteManyModel<>(filterQSampleData));
                // SampleDataDao.instance.deleteAll(filterQSampleData);
                // ApiInfoDao.instance.deleteAll(filterQSampleData);
            }
        }

        for (String deleteStaticUrl: result.deleteStaticUrls) {
            Method delMethod = Method.fromString(deleteStaticUrl.split(" ")[0]);
            String delEndpoint = deleteStaticUrl.split(" ")[1];  
            Bson filterQ = Filters.and(
                Filters.eq("apiCollectionId", apiCollectionId),
                Filters.eq("method", delMethod.name()),
                Filters.eq("url", delEndpoint)
            );

            Bson filterQSampleData = Filters.and(
                Filters.eq("_id.apiCollectionId", apiCollectionId),
                Filters.eq("_id.method", delMethod.name()),
                Filters.eq("_id.url", delEndpoint)
            );

            bulkUpdatesForSti.add(new DeleteManyModel<>(filterQ));
            bulkUpdatesForSampleData.add(new DeleteManyModel<>(filterQSampleData));
            // SingleTypeInfoDao.instance.deleteAll(filterQ);
            // SampleDataDao.instance.deleteAll(filterQSampleData);
        }

        if (bulkUpdatesForSti.size() > 0) {
            SingleTypeInfoDao.instance.getMCollection().bulkWrite(bulkUpdatesForSti, new BulkWriteOptions().ordered(false));
        }

        if (bulkUpdatesForSampleData.size() > 0) {
            SampleDataDao.instance.getMCollection().bulkWrite(bulkUpdatesForSampleData, new BulkWriteOptions().ordered(false));
        }

        if (bulkUpdatesForApiInfo.size() > 0) {
            ApiInfoDao.instance.getMCollection().bulkWrite(bulkUpdatesForApiInfo, new BulkWriteOptions().ordered(false));
        }
    }

    private void tryWithKnownURLTemplates(
        Map<URLStatic, RequestTemplate> pendingRequests, 
        APICatalog deltaCatalog,
        APICatalog dbCatalog,
        int apiCollectionId
    ) {
        Iterator<Map.Entry<URLStatic, RequestTemplate>> iterator = pendingRequests.entrySet().iterator();
        try {
            while (iterator.hasNext()) {
                Map.Entry<URLStatic, RequestTemplate> entry = iterator.next();
                URLStatic newUrl = entry.getKey();
                RequestTemplate newRequestTemplate = entry.getValue();

                for (URLTemplate  urlTemplate: dbCatalog.getTemplateURLToMethods().keySet()) {
                    if (urlTemplate.match(newUrl)) {
                        RequestTemplate alreadyInDelta = deltaCatalog.getTemplateURLToMethods().get(urlTemplate);

                        if (alreadyInDelta != null) {
                            alreadyInDelta.fillUrlParams(tokenize(newUrl.getUrl()), urlTemplate, apiCollectionId);
                            alreadyInDelta.mergeFrom(newRequestTemplate);
                        } else {
                            RequestTemplate dbTemplate = dbCatalog.getTemplateURLToMethods().get(urlTemplate);
                            RequestTemplate dbCopy = dbTemplate.copy();
                            dbCopy.mergeFrom(newRequestTemplate);
                            dbCopy.fillUrlParams(tokenize(newUrl.getUrl()), urlTemplate, apiCollectionId);
                            deltaCatalog.getTemplateURLToMethods().put(urlTemplate, dbCopy);
                        }
                        iterator.remove();
                        break;
                    }
                }
            }
        } catch (Exception e) {

        }
    }


    private Map<URLStatic, RequestTemplate> createRequestTemplates(URLAggregator aggregator) {
        Map<URLStatic, RequestTemplate> ret = new HashMap<>();
        List<SingleTypeInfo> deletedInfo = new ArrayList<>();
        Iterator<Map.Entry<URLStatic, Set<HttpResponseParams>>> iterator = aggregator.urls.entrySet().iterator();
        try {
            while (iterator.hasNext()) {
                Map.Entry<URLStatic, Set<HttpResponseParams>> entry = iterator.next();
                URLStatic url = entry.getKey();
                Set<HttpResponseParams> responseParamsList = entry.getValue();
                RequestTemplate requestTemplate = ret.get(url);
                if (requestTemplate == null) {
                    requestTemplate = new RequestTemplate(new HashMap<>(), new HashMap<>(), new HashMap<>(), new TrafficRecorder(new HashMap<>()));
                    ret.put(url, requestTemplate);
                }
                processResponse(requestTemplate, responseParamsList, deletedInfo);
                iterator.remove();
            }
        } catch (Exception e) {
            loggerMaker.errorAndAddToDb(e.toString(), LogDb.RUNTIME);
        }

        return ret;
    }

    private void processKnownStaticURLs(URLAggregator aggregator, APICatalog deltaCatalog, APICatalog dbCatalog) {
        Iterator<Map.Entry<URLStatic, Set<HttpResponseParams>>> iterator = aggregator.urls.entrySet().iterator();
        List<SingleTypeInfo> deletedInfo = deltaCatalog.getDeletedInfo();
        try {
            while (iterator.hasNext()) {
                Map.Entry<URLStatic, Set<HttpResponseParams>> entry = iterator.next();
                URLStatic url = entry.getKey();
                Set<HttpResponseParams> responseParamsList = entry.getValue();

                RequestTemplate strictMatch = dbCatalog.getStrictURLToMethods().get(url);
                if (strictMatch != null) {
                    Map<URLStatic, RequestTemplate> deltaCatalogStrictURLToMethods = deltaCatalog.getStrictURLToMethods();
                    RequestTemplate requestTemplate = deltaCatalogStrictURLToMethods.get(url);
                    if (requestTemplate == null) {
                        requestTemplate = strictMatch.copy(); // to further process the requestTemplate
                        deltaCatalogStrictURLToMethods.put(url, requestTemplate) ;
                        strictMatch.mergeFrom(requestTemplate); // to update the existing requestTemplate in db with new data
                    }

                    processResponse(requestTemplate, responseParamsList, deletedInfo);
                    iterator.remove();
                }

            }
        } catch (Exception e) {
            loggerMaker.errorAndAddToDb(e.toString(),LogDb.RUNTIME);
        }
    }

    public static String trim(String url) {
        // if (mergeAsyncOutside) {
        //     if ( !(url.startsWith("/") ) && !( url.startsWith("http") || url.startsWith("ftp")) ){
        //         url = "/" + url;
        //     }
        // } else {
            if (url.startsWith("/")) url = url.substring(1, url.length());
        // }
        
        if (url.endsWith("/")) url = url.substring(0, url.length()-1);
        return url;
    }

    private Map<Integer, Map<URLStatic, RequestTemplate>> groupByTokenSize(APICatalog catalog) {
        Map<Integer, Map<URLStatic, RequestTemplate>> sizeToURL = new HashMap<>();
        for(URLStatic rawURL: catalog.getStrictURLToMethods().keySet()) {
            RequestTemplate reqTemplate = catalog.getStrictURLToMethods().get(rawURL);
            if (reqTemplate.getUserIds().size() < 5) {
                String url = trim(rawURL.getUrl());
                String[] tokens = url.split("/");
                Map<URLStatic, RequestTemplate> urlSet = sizeToURL.get(tokens.length);
                urlSet = sizeToURL.get(tokens.length);
                if (urlSet == null) {
                    urlSet = new HashMap<>();
                    sizeToURL.put(tokens.length, urlSet);
                }

                urlSet.put(rawURL, reqTemplate);
            }
        }

        return sizeToURL;
    }

    public static String[] tokenize(String url) {
        return trim(url).split("/");
    }

    Map<String, SingleTypeInfo> convertToMap(List<SingleTypeInfo> l) {
        Map<String, SingleTypeInfo> ret = new HashMap<>();
        for(SingleTypeInfo e: l) {
            ret.put(e.composeKey(), e);
        }

        return ret;
    }

    public ArrayList<WriteModel<SampleData>> getDBUpdatesForSampleData(int apiCollectionId, APICatalog currentDelta, APICatalog dbCatalog ,boolean redactSampleData, boolean forceUpdate ) {
        List<SampleData> sampleData = new ArrayList<>();
        Map<URLStatic, RequestTemplate> deltaStrictURLToMethods = currentDelta.getStrictURLToMethods();
        Map<URLStatic, RequestTemplate> dbStrictURLToMethods = dbCatalog.getStrictURLToMethods();

        for(Map.Entry<URLStatic, RequestTemplate> entry: deltaStrictURLToMethods.entrySet()) {
            if (forceUpdate || !dbStrictURLToMethods.containsKey(entry.getKey())) {
                Key key = new Key(apiCollectionId, entry.getKey().getUrl(), entry.getKey().getMethod(), -1, 0, 0);
                sampleData.add(new SampleData(key, entry.getValue().removeAllSampleMessage()));
            }
        }

        Map<URLTemplate, RequestTemplate> deltaTemplateURLToMethods = currentDelta.getTemplateURLToMethods();
        Map<URLTemplate, RequestTemplate> dbTemplateURLToMethods = dbCatalog.getTemplateURLToMethods();

        for(Map.Entry<URLTemplate, RequestTemplate> entry: deltaTemplateURLToMethods.entrySet()) {
            if (forceUpdate || !dbTemplateURLToMethods.containsKey(entry.getKey())) {
                Key key = new Key(apiCollectionId, entry.getKey().getTemplateString(), entry.getKey().getMethod(), -1, 0, 0);
                sampleData.add(new SampleData(key, entry.getValue().removeAllSampleMessage()));
            }
        }

        ArrayList<WriteModel<SampleData>> bulkUpdates = new ArrayList<>();
        for (SampleData sample: sampleData) {
            if (sample.getSamples().size() == 0) {
                continue;
            }
            List<String> finalSamples = new ArrayList<>();
            for (String s: sample.getSamples()) {
                boolean finalRedact = redactSampleData;
                if (finalRedact) {
                    try {
                        HttpResponseParams httpResponseParams = HttpCallParser.parseKafkaMessage(s);
                        Source source = httpResponseParams.getSource();
                        if (source.equals(Source.HAR) || source.equals(Source.PCAP)) finalRedact = false;
                    } catch (Exception e1) {
                        e1.printStackTrace();
                        continue;
                    }
                }

                try {
                    if (finalRedact) {
                        String redact = RedactSampleData.redact(s);
                        finalSamples.add(redact);
                    } else {
                        finalSamples.add(s);
                    }
                } catch (Exception e) {
                    ;
                }
            }
            Bson bson = Updates.pushEach("samples", finalSamples, new PushOptions().slice(-10));

            bulkUpdates.add(
                new UpdateOneModel<>(Filters.eq("_id", sample.getId()), bson, new UpdateOptions().upsert(true))
            );
        }

        return bulkUpdates;        
    }




    public ArrayList<WriteModel<TrafficInfo>> getDBUpdatesForTraffic(int apiCollectionId, APICatalog currentDelta) {

        List<TrafficInfo> trafficInfos = new ArrayList<>();
        for(Map.Entry<URLStatic, RequestTemplate> entry: currentDelta.getStrictURLToMethods().entrySet()) {
            trafficInfos.addAll(entry.getValue().removeAllTrafficInfo(apiCollectionId, entry.getKey().getUrl(), entry.getKey().getMethod(), -1));
        }

        for(Map.Entry<URLTemplate, RequestTemplate> entry: currentDelta.getTemplateURLToMethods().entrySet()) {
            trafficInfos.addAll(entry.getValue().removeAllTrafficInfo(apiCollectionId, entry.getKey().getTemplateString(), entry.getKey().getMethod(), -1));
        }

        ArrayList<WriteModel<TrafficInfo>> bulkUpdates = new ArrayList<>();
        for (TrafficInfo trafficInfo: trafficInfos) {
            List<Bson> updates = new ArrayList<>();

            for (Map.Entry<String, Integer> entry: trafficInfo.mapHoursToCount.entrySet()) {
                updates.add(Updates.inc("mapHoursToCount."+entry.getKey(), entry.getValue())); 
            }

            bulkUpdates.add(
                new UpdateOneModel<>(Filters.eq("_id", trafficInfo.getId()), Updates.combine(updates), new UpdateOptions().upsert(true))
            );
        }

        return bulkUpdates;
    }

    public DbUpdateReturn getDBUpdatesForParams(APICatalog currentDelta, APICatalog currentState, boolean redactSampleData) {
        Map<String, SingleTypeInfo> dbInfoMap = convertToMap(currentState.getAllTypeInfo());
        Map<String, SingleTypeInfo> deltaInfoMap = convertToMap(currentDelta.getAllTypeInfo());

        ArrayList<WriteModel<SingleTypeInfo>> bulkUpdates = new ArrayList<>();
        ArrayList<WriteModel<SensitiveSampleData>> bulkUpdatesForSampleData = new ArrayList<>();
        int now = Context.now();
        for(String key: deltaInfoMap.keySet()) {
            SingleTypeInfo dbInfo = dbInfoMap.get(key);
            SingleTypeInfo deltaInfo = deltaInfoMap.get(key);
            Bson update;

            int inc = deltaInfo.getCount() - (dbInfo == null ? 0 : dbInfo.getCount());
            long lastSeenDiff = deltaInfo.getLastSeen() - (dbInfo == null ? 0 : dbInfo.getLastSeen());
            boolean minMaxChanged = (dbInfo == null) || (dbInfo.getMinValue() != deltaInfo.getMinValue()) || (dbInfo.getMaxValue() != deltaInfo.getMaxValue());
            boolean valuesChanged = (dbInfo == null) || (dbInfo.getValues().count() != deltaInfo.getValues().count());

            if (inc == 0 && lastSeenDiff < (60*30) && !minMaxChanged && !valuesChanged) {
                continue;
            } else {
                inc = 1;
            }

            int oldTs = dbInfo == null ? 0 : dbInfo.getTimestamp();

            update = Updates.inc("count", inc);

            if (oldTs == 0) {
                update = Updates.combine(update, Updates.setOnInsert("timestamp", now));
            }

            update = Updates.combine(update, Updates.max(SingleTypeInfo.LAST_SEEN, deltaInfo.getLastSeen()));
            update = Updates.combine(update, Updates.max(SingleTypeInfo.MAX_VALUE, deltaInfo.getMaxValue()));
            update = Updates.combine(update, Updates.min(SingleTypeInfo.MIN_VALUE, deltaInfo.getMinValue()));

            if (dbInfo != null) {
                SingleTypeInfo.Domain domain = dbInfo.getDomain();
                if (domain ==  SingleTypeInfo.Domain.ENUM) {
                    CappedSet<String> values = dbInfo.getValues();
                    Set<String> elements = new HashSet<>();
                    if (values != null) {
                        elements = values.getElements();
                    }
                    int valuesSize = elements.size();
                    if (valuesSize >= SingleTypeInfo.VALUES_LIMIT) {
                        SingleTypeInfo.Domain newDomain;
                        if (dbInfo.getSubType().equals(SingleTypeInfo.INTEGER_32) || dbInfo.getSubType().equals(SingleTypeInfo.INTEGER_64) || dbInfo.getSubType().equals(SingleTypeInfo.FLOAT)) {
                            newDomain = SingleTypeInfo.Domain.RANGE;
                        } else {
                            newDomain = SingleTypeInfo.Domain.ANY;
                        }
                        update = Updates.combine(update, Updates.set(SingleTypeInfo._DOMAIN, newDomain));
                    }
                } else {
                    deltaInfo.setDomain(dbInfo.getDomain());
                    deltaInfo.setValues(new CappedSet<>());
                    if (!dbInfo.getValues().getElements().isEmpty()) {
                        Bson bson = Updates.set(SingleTypeInfo._VALUES +".elements",new ArrayList<>());
                        update = Updates.combine(update, bson);
                    }
                }
            }

            if (dbInfo == null || dbInfo.getDomain() == SingleTypeInfo.Domain.ENUM) {
                CappedSet<String> values = deltaInfo.getValues();
                if (values != null) {
                    Set<String> elements = new HashSet<>();
                    for (String el: values.getElements()) {
                        if (redactSampleData) {
                            elements.add(el.hashCode()+"");
                        } else {
                            elements.add(el);
                        }
                    }
                    Bson bson = Updates.addEachToSet(SingleTypeInfo._VALUES +".elements",new ArrayList<>(elements));
                    update = Updates.combine(update, bson);
                    deltaInfo.setValues(new CappedSet<>());
                }
            }


            if (!redactSampleData && deltaInfo.getExamples() != null && !deltaInfo.getExamples().isEmpty()) {
                Bson bson = Updates.pushEach(SensitiveSampleData.SAMPLE_DATA, Arrays.asList(deltaInfo.getExamples().toArray()), new PushOptions().slice(-1 *SensitiveSampleData.cap));
                bulkUpdatesForSampleData.add(
                        new UpdateOneModel<>(
                                SensitiveSampleDataDao.getFilters(deltaInfo),
                                bson,
                                new UpdateOptions().upsert(true)
                        )
                );
            }

            Bson updateKey = SingleTypeInfoDao.createFilters(deltaInfo);

            bulkUpdates.add(new UpdateOneModel<>(updateKey, update, new UpdateOptions().upsert(true)));
        }

        for(SingleTypeInfo deleted: currentDelta.getDeletedInfo()) {
            currentDelta.getStrictURLToMethods().remove(new URLStatic(deleted.getUrl(), Method.fromString(deleted.getMethod())));
            bulkUpdates.add(new DeleteOneModel<>(SingleTypeInfoDao.createFilters(deleted), new DeleteOptions()));
            bulkUpdatesForSampleData.add(new DeleteOneModel<>(SensitiveSampleDataDao.getFilters(deleted), new DeleteOptions()));
        }


        ArrayList<WriteModel<SensitiveParamInfo>> bulkUpdatesForSensitiveParamInfo = new ArrayList<>();
        for (SensitiveParamInfo sensitiveParamInfo: sensitiveParamInfoBooleanMap.keySet()) {
            if (!sensitiveParamInfoBooleanMap.get(sensitiveParamInfo)) continue;
            bulkUpdatesForSensitiveParamInfo.add(
                    new UpdateOneModel<SensitiveParamInfo>(
                            SensitiveParamInfoDao.getFilters(sensitiveParamInfo),
                            Updates.set(SensitiveParamInfo.SAMPLE_DATA_SAVED, true),
                            new UpdateOptions().upsert(false)
                    )
            );
        }

        return new DbUpdateReturn(bulkUpdates, bulkUpdatesForSampleData, bulkUpdatesForSensitiveParamInfo);
    }

    public static class DbUpdateReturn {
        public ArrayList<WriteModel<SingleTypeInfo>> bulkUpdatesForSingleTypeInfo;
        public ArrayList<WriteModel<SensitiveSampleData>> bulkUpdatesForSampleData;
        public ArrayList<WriteModel<SensitiveParamInfo>> bulkUpdatesForSensitiveParamInfo = new ArrayList<>();

        public DbUpdateReturn(ArrayList<WriteModel<SingleTypeInfo>> bulkUpdatesForSingleTypeInfo,
                              ArrayList<WriteModel<SensitiveSampleData>> bulkUpdatesForSampleData,
                              ArrayList<WriteModel<SensitiveParamInfo>> bulkUpdatesForSensitiveParamInfo
        ) {
            this.bulkUpdatesForSingleTypeInfo = bulkUpdatesForSingleTypeInfo;
            this.bulkUpdatesForSampleData = bulkUpdatesForSampleData;
            this.bulkUpdatesForSensitiveParamInfo = bulkUpdatesForSensitiveParamInfo;
        }
    }


    public static String[] trimAndSplit(String url) {
        return trim(url).split("/");
    }

    public static URLTemplate createUrlTemplate(String url, Method method) {
        String[] tokens = trimAndSplit(url);
        SuperType[] types = new SuperType[tokens.length];
        for(int i = 0; i < tokens.length; i ++ ) {
            String token = tokens[i];

            if (token.equals("STRING")) {
                tokens[i] = null;
                types[i] = SuperType.STRING;
            } else if (token.equals("INTEGER")) {
                tokens[i] = null;
                types[i] = SuperType.INTEGER;
            } else {
                types[i] = null;
            }

        }

        URLTemplate urlTemplate = new URLTemplate(tokens, types, method);

        return urlTemplate;
    }

    private int lastMergeAsyncOutsideTs = 0;
    public void buildFromDB(boolean calcDiff, boolean fetchAllSTI) {

        loggerMaker.infoAndAddToDb("Started building from dB", LogDb.RUNTIME);
        if (mergeAsyncOutside) {
            if (Context.now() - lastMergeAsyncOutsideTs > 600) {
                this.lastMergeAsyncOutsideTs = Context.now();

                boolean gotDibs = Cluster.callDibs(Cluster.RUNTIME_MERGER, 1800, 60);
                if (gotDibs) {
                    BackwardCompatibility backwardCompatibility = BackwardCompatibilityDao.instance.findOne(new BasicDBObject());
                    if (backwardCompatibility.getMergeOnHostInit() == 0) {
                        new MergeOnHostOnly().mergeHosts();
                        Bson update = Updates.set(BackwardCompatibility.MERGE_ON_HOST_INIT, Context.now());
                        BackwardCompatibilityDao.instance.getMCollection().updateMany(new BasicDBObject(), update);
                    }

                    try {
                        List<ApiCollection> allCollections = ApiCollectionsDao.instance.getMetaAll();
                        Boolean urlRegexMatchingEnabled = AccountSettingsDao.instance.findOne(AccountSettingsDao.generateFilter()).getUrlRegexMatchingEnabled();
                        loggerMaker.infoAndAddToDb("url regex matching enabled status is " + urlRegexMatchingEnabled, LogDb.RUNTIME);
                        for(ApiCollection apiCollection: allCollections) {
                            int start = Context.now();
                            loggerMaker.infoAndAddToDb("Started merging API collection " + apiCollection.getId(), LogDb.RUNTIME);
                            mergeUrlsAndSave(apiCollection.getId(), urlRegexMatchingEnabled);
                            loggerMaker.infoAndAddToDb("Finished merging API collection " + apiCollection.getId() + " in " + (Context.now() - start) + " seconds", LogDb.RUNTIME);
                        }
                    } catch (Exception e) {
                        ;
                    }
                }
            }
        }

        List<SingleTypeInfo> allParams;
        if (fetchAllSTI) {
            allParams = SingleTypeInfoDao.instance.fetchAll();
        } else {
            List<Integer> apiCollectionIds = ApiCollectionsDao.instance.fetchNonTrafficApiCollectionsIds();
            allParams = SingleTypeInfoDao.instance.fetchStiOfCollections(apiCollectionIds);
        }
        this.dbState = build(allParams);
        this.sensitiveParamInfoBooleanMap = new HashMap<>();
        List<SensitiveParamInfo> sensitiveParamInfos = SensitiveParamInfoDao.instance.getUnsavedSensitiveParamInfos();
        for (SensitiveParamInfo sensitiveParamInfo: sensitiveParamInfos) {
            this.sensitiveParamInfoBooleanMap.put(sensitiveParamInfo, false);
        }

        if (mergeAsyncOutside) {
            this.delta = new HashMap<>();
            return;
        }

        if(calcDiff) {
            for(int collectionId: this.dbState.keySet()) {
                APICatalog newCatalog = this.dbState.get(collectionId);
                Set<String> newURLs = new HashSet<>();
                for(URLTemplate url: newCatalog.getTemplateURLToMethods().keySet()) { 
                    newURLs.add(url.getTemplateString()+ " "+ url.getMethod().name());
                }
                for(URLStatic url: newCatalog.getStrictURLToMethods().keySet()) { 
                    newURLs.add(url.getUrl()+ " "+ url.getMethod().name());
                }

                Bson findQ = Filters.eq("_id", collectionId);

                ApiCollectionsDao.instance.getMCollection().updateOne(findQ, Updates.set("urls", newURLs));
            }
        } else {

            for(Map.Entry<Integer, APICatalog> entry: this.dbState.entrySet()) {
                int apiCollectionId = entry.getKey();
                APICatalog apiCatalog = entry.getValue();
                for(URLTemplate urlTemplate: apiCatalog.getTemplateURLToMethods().keySet()) {
                    Iterator<Map.Entry<URLStatic, RequestTemplate>> staticURLIterator = apiCatalog.getStrictURLToMethods().entrySet().iterator();
                    while(staticURLIterator.hasNext()){
                        Map.Entry<URLStatic, RequestTemplate> urlXTemplate = staticURLIterator.next();
                        URLStatic urlStatic = urlXTemplate.getKey();
                        RequestTemplate requestTemplate = urlXTemplate.getValue();
                        if (urlTemplate.match(urlStatic)) {
                            if (this.delta == null) {
                                this.delta = new HashMap<>();
                            }

                            if (this.getDelta(apiCollectionId) == null) {
                                this.delta.put(apiCollectionId, new APICatalog(apiCollectionId, new HashMap<>(), new HashMap<>()));
                            }

                            this.getDelta(apiCollectionId).getDeletedInfo().addAll(requestTemplate.getAllTypeInfo());
                            staticURLIterator.remove();
                        }
                    }
                }
            }
        }
    }

    private static void buildHelper(SingleTypeInfo param, Map<Integer, APICatalog> ret) {
        String url = param.getUrl();
        int collId = param.getApiCollectionId();
        APICatalog catalog = ret.get(collId);

        if (catalog == null) {
            catalog = new APICatalog(collId, new HashMap<>(), new HashMap<>());
            ret.put(collId, catalog);
        }
        RequestTemplate reqTemplate;
        if (APICatalog.isTemplateUrl(url)) {
            URLTemplate urlTemplate = createUrlTemplate(url, Method.fromString(param.getMethod()));
            reqTemplate = catalog.getTemplateURLToMethods().get(urlTemplate);

            if (reqTemplate == null) {
                reqTemplate = new RequestTemplate(new HashMap<>(), new HashMap<>(), new HashMap<>(), new TrafficRecorder(new HashMap<>()));
                catalog.getTemplateURLToMethods().put(urlTemplate, reqTemplate);
            }

        } else {
            URLStatic urlStatic = new URLStatic(url, Method.fromString(param.getMethod()));
            reqTemplate = catalog.getStrictURLToMethods().get(urlStatic);
            if (reqTemplate == null) {
                reqTemplate = new RequestTemplate(new HashMap<>(), new HashMap<>(), new HashMap<>(), new TrafficRecorder(new HashMap<>()));
                catalog.getStrictURLToMethods().put(urlStatic, reqTemplate);
            }
        }

        if (param.getIsUrlParam()) {
            Map<Integer, KeyTypes> urlParams = reqTemplate.getUrlParams();
            if (urlParams == null) {
                urlParams = new HashMap<>();
                reqTemplate.setUrlParams(urlParams);
            }

            String p = param.getParam();
            try {
                int position = Integer.parseInt(p);
                KeyTypes keyTypes = urlParams.get(position);
                if (keyTypes == null) {
                    keyTypes = new KeyTypes(new HashMap<>(), false);
                    urlParams.put(position, keyTypes);
                }
                keyTypes.getOccurrences().put(param.getSubType(), param);
            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("ERROR while parsing url param position: " + p, LogDb.RUNTIME);
            }
            return;
        }

        if (param.getResponseCode() > 0) {
            RequestTemplate respTemplate = reqTemplate.getResponseTemplates().get(param.getResponseCode());
            if (respTemplate == null) {
                respTemplate = new RequestTemplate(new HashMap<>(), new HashMap<>(), new HashMap<>(), new TrafficRecorder(new HashMap<>()));
                reqTemplate.getResponseTemplates().put(param.getResponseCode(), respTemplate);
            }

            reqTemplate = respTemplate;
        }

        Map<String, KeyTypes> keyTypesMap = param.getIsHeader() ? reqTemplate.getHeaders() : reqTemplate.getParameters();
        KeyTypes keyTypes = keyTypesMap.get(param.getParam());

        if (keyTypes == null) {
            keyTypes = new KeyTypes(new HashMap<>(), false);

            if (param.getParam() == null) {
                logger.info("null value - " + param.composeKey());
            }

            keyTypesMap.put(param.getParam(), keyTypes);
        }

        SingleTypeInfo info = keyTypes.getOccurrences().get(param.getSubType());
        if (info != null && info.getTimestamp() > param.getTimestamp()) {
            param = info;
        }

        keyTypes.getOccurrences().put(param.getSubType(), param);
    }


    private static Map<Integer, APICatalog> build(List<SingleTypeInfo> allParams) {
        Map<Integer, APICatalog> ret = new HashMap<>();
        
        for (SingleTypeInfo param: allParams) {
            try {
                buildHelper(param, ret);
            } catch (Exception e) {
                e.printStackTrace();
                loggerMaker.errorAndAddToDb("Error while building from db: " + e.getMessage(), LogDb.RUNTIME);
            }
        }

        return ret;
    }

    int counter = 0;
    
    public void syncWithDB(boolean syncImmediately, boolean fetchAllSTI) {
        List<WriteModel<SingleTypeInfo>> writesForParams = new ArrayList<>();
        List<WriteModel<SensitiveSampleData>> writesForSensitiveSampleData = new ArrayList<>();
        List<WriteModel<TrafficInfo>> writesForTraffic = new ArrayList<>();
        List<WriteModel<SampleData>> writesForSampleData = new ArrayList<>();
        List<WriteModel<SensitiveParamInfo>> writesForSensitiveParamInfo = new ArrayList<>();

        AccountSettings accountSettings = AccountSettingsDao.instance.findOne(AccountSettingsDao.generateFilter());

        boolean redact = false;
        if (accountSettings != null) {
            redact =  accountSettings.isRedactPayload();
        }

        counter++;
        for(int apiCollectionId: this.delta.keySet()) {
            APICatalog deltaCatalog = this.delta.get(apiCollectionId);
            APICatalog dbCatalog = this.dbState.getOrDefault(apiCollectionId, new APICatalog(apiCollectionId, new HashMap<>(), new HashMap<>()));
            DbUpdateReturn dbUpdateReturn = getDBUpdatesForParams(deltaCatalog, dbCatalog, redact);
            writesForParams.addAll(dbUpdateReturn.bulkUpdatesForSingleTypeInfo);
            writesForSensitiveSampleData.addAll(dbUpdateReturn.bulkUpdatesForSampleData);
            writesForSensitiveParamInfo.addAll(dbUpdateReturn.bulkUpdatesForSensitiveParamInfo);
            writesForTraffic.addAll(getDBUpdatesForTraffic(apiCollectionId, deltaCatalog));
            deltaCatalog.setDeletedInfo(new ArrayList<>());

            boolean forceUpdate = syncImmediately || counter % 10 == 0;
            writesForSampleData.addAll(getDBUpdatesForSampleData(apiCollectionId, deltaCatalog,dbCatalog, redact, forceUpdate));
        }

        loggerMaker.infoAndAddToDb("adding " + writesForParams.size() + " updates for params", LogDb.RUNTIME);
        int from = 0;
        int batch = 10000;

        long start = System.currentTimeMillis();
        if (writesForParams.size() >0) {
            do {

                List<WriteModel<SingleTypeInfo>> slicedWrites = writesForParams.subList(from, Math.min(from + batch, writesForParams.size()));
                from += batch;
                BulkWriteResult res =
                        SingleTypeInfoDao.instance.getMCollection().bulkWrite(
                                slicedWrites,
                                new BulkWriteOptions().ordered(true).bypassDocumentValidation(false)
                        );

                loggerMaker.infoAndAddToDb((System.currentTimeMillis() - start) + ": " + res.getInserts().size() + " " + res.getUpserts().size(), LogDb.RUNTIME);
            } while (from < writesForParams.size());
        }

        loggerMaker.infoAndAddToDb("adding " + writesForTraffic.size() + " updates for traffic", LogDb.RUNTIME);
        if(writesForTraffic.size() > 0) {
            BulkWriteResult res = TrafficInfoDao.instance.getMCollection().bulkWrite(writesForTraffic);

            loggerMaker.infoAndAddToDb(res.getInserts().size() + " " +res.getUpserts().size(), LogDb.RUNTIME);

        }
        

        loggerMaker.infoAndAddToDb("adding " + writesForSampleData.size() + " updates for samples", LogDb.RUNTIME);
        if(writesForSampleData.size() > 0) {
            BulkWriteResult res = SampleDataDao.instance.getMCollection().bulkWrite(writesForSampleData);

            loggerMaker.infoAndAddToDb(res.getInserts().size() + " " +res.getUpserts().size(), LogDb.RUNTIME);

        }

        if (writesForSensitiveSampleData.size() > 0) {
            SensitiveSampleDataDao.instance.getMCollection().bulkWrite(writesForSensitiveSampleData);
        }

        if (writesForSensitiveParamInfo.size() > 0) {
            SensitiveParamInfoDao.instance.getMCollection().bulkWrite(writesForSensitiveParamInfo);
        }

        buildFromDB(true, fetchAllSTI);
    }

    public void printNewURLsInDelta(APICatalog deltaCatalog) {
        for(URLStatic s: deltaCatalog.getStrictURLToMethods().keySet()) {
            logger.info(s.getUrl());
        }

        for(URLTemplate s: deltaCatalog.getTemplateURLToMethods().keySet()) {
            logger.info(s.getTemplateString());
        }
    }


    public APICatalog getDelta(int apiCollectionId) {
        return this.delta.get(apiCollectionId);
    }


    public APICatalog getDbState(int apiCollectionId) {
        return this.dbState.get(apiCollectionId);
    }
}
