/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.resources;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaAccept;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.UniqueIdentifier;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.Version;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.registry.ResponseCache;
import com.netflix.eureka.registry.Key.KeyType;
import com.netflix.eureka.registry.Key;
import com.netflix.eureka.util.EurekaMonitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A <em>jersey</em> resource that handles request related to a particular
 * {@link com.netflix.discovery.shared.Application}.
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 * Rersey框架相当于SpringMVC框架
 * Resource相当于Controller
 *
 * ApplicationResource:
 *      服务注册
 *
 */
@Produces({"application/xml", "application/json"})
public class ApplicationResource {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationResource.class);

    private final String appName;
    private final EurekaServerConfig serverConfig;
    private final PeerAwareInstanceRegistry registry;
    private final ResponseCache responseCache;

    ApplicationResource(String appName,
                        EurekaServerConfig serverConfig,
                        PeerAwareInstanceRegistry registry) {
        this.appName = appName.toUpperCase();
        this.serverConfig = serverConfig;
        this.registry = registry;
        this.responseCache = registry.getResponseCache();
    }

    public String getAppName() {
        return appName;
    }

    /**
     * Gets information about a particular {@link com.netflix.discovery.shared.Application}.
     *
     * @param version
     *            the version of the request.
     * @param acceptHeader
     *            the accept header of the request to indicate whether to serve
     *            JSON or XML data.
     * @return the response containing information about a particular
     *         application.
     *
     * 获取有关特定对象的信息
     *
     */
    @GET
    public Response getApplication(@PathParam("version") String version,
                                   @HeaderParam("Accept") final String acceptHeader,
                                   @HeaderParam(EurekaAccept.HTTP_X_EUREKA_ACCEPT) String eurekaAccept) {
        if (!registry.shouldAllowAccess(false)) {
            return Response.status(Status.FORBIDDEN).build();
        }

        EurekaMonitors.GET_APPLICATION.increment();

        CurrentRequestVersion.set(Version.toEnum(version));
        KeyType keyType = Key.KeyType.JSON;
        if (acceptHeader == null || !acceptHeader.contains("json")) {
            keyType = Key.KeyType.XML;
        }

        /**
         * 创建缓存key
         */
        Key cacheKey = new Key(
                Key.EntityType.Application,
                appName,
                keyType,
                CurrentRequestVersion.get(),
                EurekaAccept.fromString(eurekaAccept)
        );

        /**
         * 获取缓存
         *
         * 先从只读缓存中获取，如果找不到
         * 再从读写缓存中获取，如果找不到
         * 直接从本地缓存中获取-拿出真实数据，并添加到读写缓冲中
         *
         * 读写缓存定时180s 删除过期key
         * 只读缓存默认30s 更新缓存 从读写缓存中获取数据
         */
        String payLoad = responseCache.get(cacheKey);
        CurrentRequestVersion.remove();

        if (payLoad != null) {
            logger.debug("Found: {}", appName);
            return Response.ok(payLoad).build();
        } else {
            logger.debug("Not Found: {}", appName);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * Gets information about a particular instance of an application.
     *
     * @param id
     *            the unique identifier of the instance.
     * @return information about a particular instance.
     *
     * 获取指定实例信息
     *
     */
    @Path("{id}")
    public InstanceResource getInstanceInfo(@PathParam("id") String id) {
        return new InstanceResource(this, id, serverConfig, registry);
    }

    /**
     * Registers information about a particular instance for an
     * {@link com.netflix.discovery.shared.Application}.
     *
     * @param info ： 服务实例信息
     *            {@link InstanceInfo} information of the instance.
     * @param isReplication： true:集群注册  false:客户端注册成
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     *
     *  服务注册和集群同步注册接口
     *
     */
    @POST
    @Consumes({"application/json", "application/xml"})
    public Response addInstance(InstanceInfo info,
                                @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
        logger.debug("Registering instance {} (replication={})", info.getId(), isReplication);
        // validate that the instanceinfo contains all the necessary required fields
        // 检验实例信息
        if (isBlank(info.getId())) {
            return Response.status(400).entity("Missing instanceId").build();
        } else if (isBlank(info.getHostName())) {
            return Response.status(400).entity("Missing hostname").build();
        } else if (isBlank(info.getIPAddr())) {
            return Response.status(400).entity("Missing ip address").build();
        } else if (isBlank(info.getAppName())) {
            return Response.status(400).entity("Missing appName").build();
        } else if (!appName.equals(info.getAppName())) {
            return Response.status(400).entity("Mismatched appName, expecting " + appName + " but was " + info.getAppName()).build();
        } else if (info.getDataCenterInfo() == null) {
            return Response.status(400).entity("Missing dataCenterInfo").build();
        } else if (info.getDataCenterInfo().getName() == null) {
            return Response.status(400).entity("Missing dataCenterInfo Name").build();
        }

        // handle cases where clients may be registering with bad DataCenterInfo with missing data
        // 处理客户端可能注册了错误的DataCenterInfo并丢失数据的情况
        DataCenterInfo dataCenterInfo = info.getDataCenterInfo();
        if (dataCenterInfo instanceof UniqueIdentifier) {
            String dataCenterInfoId = ((UniqueIdentifier) dataCenterInfo).getId();
            if (isBlank(dataCenterInfoId)) {
                boolean experimental = "true".equalsIgnoreCase(serverConfig.getExperimental("registration.validation.dataCenterInfoId"));
                if (experimental) {
                    String entity = "DataCenterInfo of type " + dataCenterInfo.getClass() + " must contain a valid id";
                    return Response.status(400).entity(entity).build();
                } else if (dataCenterInfo instanceof AmazonInfo) {
                    AmazonInfo amazonInfo = (AmazonInfo) dataCenterInfo;
                    String effectiveId = amazonInfo.get(AmazonInfo.MetaDataKey.instanceId);
                    if (effectiveId == null) {
                        amazonInfo.getMetadata().put(AmazonInfo.MetaDataKey.instanceId.getName(), info.getId());
                    }
                } else {
                    logger.warn("Registering DataCenterInfo of type {} without an appropriate id", dataCenterInfo.getClass());
                }
            }
        }

        //开始注册服务实例
        registry.register(info, "true".equals(isReplication));
        return Response.status(204).build();  // 204 to be backwards compatible
    }

    /**
     * Returns the application name of a particular application.
     *
     * @return the application name of a particular application.
     */
    String getName() {
        return appName;
    }

    private boolean isBlank(String str) {
        return str == null || str.isEmpty();
    }
}
