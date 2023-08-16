package com.morpheusdata.netbox

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.IPAMProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolRange
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.projection.NetworkPoolIdentityProjection
import com.morpheusdata.model.projection.NetworkPoolIpIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.apache.http.entity.ContentType
import io.reactivex.Observable
import org.apache.commons.validator.routines.InetAddressValidator

@Slf4j
class NetBoxProvider implements IPAMProvider {
	MorpheusContext morpheusContext
	Plugin plugin
    static String networksPath = 'api/ipam/ip-ranges/'
    static String getIpsPath = 'api/ipam/ip-addresses/'
    static String authPath = 'api/users/tokens/provision/'

	static String LOCK_NAME = 'netbox.ipam'
	private java.lang.Object maxResults

	NetBoxProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.morpheusContext = morpheusContext
		this.plugin = plugin
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return 'netbox'
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'NetBox'
	}

	/**
	 * Validation Method used to validate all inputs applied to the integration of an IPAM Provider upon save.
	 * If an input fails validation or authentication information cannot be verified, Error messages should be returned
	 * via a {@link ServiceResponse} object where the key on the error is the field name and the value is the error message.
	 * If the error is a generic authentication error or unknown error, a standard message can also be sent back in the response.
	 *
	 * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
	 * @return A response is returned depending on if the inputs are valid or not.
	 */
	@Override
	ServiceResponse verifyNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		ServiceResponse<NetworkPoolServer> rtn = ServiceResponse.error()
		rtn.errors = [:]
        if(!poolServer.name || poolServer.name == ''){
            rtn.errors['name'] = 'name is required'
        }
		if(!poolServer.serviceUrl || poolServer.serviceUrl == ''){
			rtn.errors['serviceUrl'] = 'NetBox API URL is required'
		}
		if((!poolServer.serviceUsername || poolServer.serviceUsername == '') && (!poolServer.credentialData?.username || poolServer.credentialData?.username == '')){
			rtn.errors['serviceUsername'] = 'username is required'
		}
		if((!poolServer.servicePassword || poolServer.servicePassword == '') && (!poolServer.credentialData?.password || poolServer.credentialData?.password == '')){
			rtn.errors['servicePassword'] = 'password is required'
		}

		rtn.data = poolServer
		if(rtn.errors.size() > 0){
			rtn.success = false
			return rtn //
		}
        def rpcConfig = getRpcConfig(poolServer)
		HttpApiClient netboxClient = new HttpApiClient()
        def tokenResults
		try {
			def apiUrl = cleanServiceUrl(poolServer.serviceUrl)
			boolean hostOnline = false
			try {
				def apiUrlObj = new URL(apiUrl)
				def apiHost = apiUrlObj.host
				def apiPort = apiUrlObj.port > 0 ? apiUrlObj.port : (apiUrlObj?.protocol?.toLowerCase() == 'https' ? 443 : 80)
				hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, null)
			} catch(e) {
				log.error("Error parsing URL {}", apiUrl, e)
			}
			if(hostOnline) {
				opts.doPaging = false
				opts.maxResults = 1
                tokenResults = login(netboxClient,rpcConfig)
                if(tokenResults.success) {
				    def networkList = listNetworks(netboxClient,tokenResults.token.toString(),poolServer, opts)
                    if(networkList.success) {
                        rtn.success = true
                    } else {
                        rtn.msg = networkList.msg ?: 'Error connecting to NetBox'
                    }
                } else {
                    rtn.msg = tokenResults.msg ?: 'Error authenticating to NetBox'
                }
            } else {
                rtn.msg = 'Host not reachable'
            }
		} catch(e) {
			log.error("verifyPoolServer error: ${e}", e)
		} finally {
			netboxClient.shutdownClient()
		}
		return rtn
	}

	ServiceResponse<NetworkPoolServer> initializeNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		log.info("initializeNetworkPoolServer: ${poolServer.dump()}")
		def rtn = new ServiceResponse()
        try {
            if(poolServer) {
                refresh(poolServer)
                rtn.data = poolServer
            } else {
                rtn.error = 'No pool server found'
            }
        } catch(e) {
            rtn.error = "initializeNetworkPoolServer error: ${e}"
            log.error("initializeNetworkPoolServer error: ${e}", e)
        }
        return rtn
    }

	@Override
	ServiceResponse createNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		log.info "createNetworkPoolServer() no-op"
		return ServiceResponse.success() // no-op
	}

	@Override
	ServiceResponse updateNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		return ServiceResponse.success() // no-op
	}

	protected ServiceResponse refreshNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		def rtn = new ServiceResponse()
        def tokenResults
        def rpcConfig = getRpcConfig(poolServer)
		log.debug("refreshNetworkPoolServer: {}", poolServer.dump())
		HttpApiClient netboxClient = new HttpApiClient()
		netboxClient.throttleRate = poolServer.serviceThrottleRate
		try {
			def apiUrl = cleanServiceUrl(poolServer.serviceUrl)
			def apiUrlObj = new URL(apiUrl)
			def apiHost = apiUrlObj.host
			def apiPort = apiUrlObj.port > 0 ? apiUrlObj.port : (apiUrlObj?.protocol?.toLowerCase() == 'https' ? 443 : 80)
			def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, null)

			log.debug("online: {} - {}", apiHost, hostOnline)

			def testResults
			// Promise
			if(hostOnline) {
                tokenResults = login(netboxClient,rpcConfig)
                if(tokenResults.success) {
				    testResults = testNetworkPoolServer(netboxClient,tokenResults.token as String,poolServer) as ServiceResponse<Map>
                    if(!testResults.success) {
                        //NOTE invalidLogin was only ever set to false.
                        morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'error calling NetBox').blockingGet()
                    } else {
                        morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.syncing).blockingGet()
                    }
                } else {
                    morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'NetBox api not reachable')
                    return ServiceResponse.error("NetBox api not reachable")
                }
            } else {
                morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'NetBox api not reachable')
            }
			Date now = new Date()
			if(testResults?.success) {
                String token = tokenResults?.token as String
				cacheNetworks(netboxClient,token,poolServer)
				if(poolServer?.configMap?.inventoryExisting) {
					//cacheIpAddressRecords(netboxClient,token,poolServer)
				}
				log.info("Sync Completed in ${new Date().time - now.time}ms")
				morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.ok).subscribe().dispose()
			}
			return testResults
		} catch(e) {
			log.error("refreshNetworkPoolServer error: ${e}", e)
		} finally {
            if(tokenResults?.success) {
                logout(netboxClient,rpcConfig,tokenResults.token as String)
            }
			netboxClient.shutdownClient()
		}
		return rtn
	}

	// cacheNetworks methods
	void cacheNetworks(HttpApiClient client, String token, NetworkPoolServer poolServer, Map opts = [:]) {
		opts.doPaging = true
		def listResults = listNetworks(client, token, poolServer)

		if(listResults.success) {
			List apiItems = listResults.data as List<Map>
			Observable<NetworkPoolIdentityProjection> poolRecords = morpheus.network.pool.listIdentityProjections(poolServer.id)
			SyncTask<NetworkPoolIdentityProjection,Map,NetworkPool> syncTask = new SyncTask(poolRecords, apiItems as Collection<Map>)
			syncTask.addMatchFunction { NetworkPoolIdentityProjection domainObject, Map apiItem ->
				domainObject.externalId == "${apiItem.id}"
			}.onDelete {removeItems ->
				morpheus.network.pool.remove(poolServer.id, removeItems).blockingGet()
			}.onAdd { itemsToAdd ->
				addMissingPools(poolServer, itemsToAdd)
			}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkPoolIdentityProjection,Map>> updateItems ->

				Map<Long, SyncTask.UpdateItemDto<NetworkPoolIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
				return morpheus.network.pool.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkPool pool ->
					SyncTask.UpdateItemDto<NetworkPoolIdentityProjection, Map> matchItem = updateItemMap[pool.id]
					return new SyncTask.UpdateItem<NetworkPool,Map>(existingItem:pool, masterItem:matchItem.masterItem)
				}

			}.onUpdate { List<SyncTask.UpdateItem<NetworkPool,Map>> updateItems ->
				updateMatchedPools(poolServer, updateItems)
			}.start()
		}
	}

	void addMissingPools(NetworkPoolServer poolServer, Collection<Map> chunkedAddList) {
		def poolType = new NetworkPoolType(code: 'netbox')
		def poolTypeIpv6 = new NetworkPoolType(code: 'netboxipv6')
		List<NetworkPool> missingPoolsList = []
		chunkedAddList?.each { Map it ->
			def networkIp = it.display
			def newNetworkPool
            def startAddress = it.start_address.tokenize('/')[0]
            def endAddress = it.end_address.tokenize('/')[0]
            def size = it.size
			def rangeConfig
            def addRange
			if(it.family.value == 4) {
                def addConfig = [account:poolServer.account, poolServer:poolServer, owner:poolServer.account, name:it.display, externalId:"${it.id}",
                                 cidr: startAddress, type: poolType, poolEnabled:true, parentType:'NetworkPoolServer', parentId:poolServer.id,
                                 ipFreeCount: size,ipCount: size]
                newNetworkPool = new NetworkPool(addConfig)
                newNetworkPool.ipRanges = []
                rangeConfig = [startAddress:startAddress, endAddress:endAddress, addressCount:size]
                addRange = new NetworkPoolRange(rangeConfig)
                newNetworkPool.ipRanges.add(addRange)
                }
            if(it.family.value == 6) {
				def addConfig = [account:poolServer.account, poolServer:poolServer, owner:poolServer.account, name:it.display, externalId:"${it.id}",
                                 cidr: startAddress, type: poolTypeIpv6, poolEnabled:true, parentType:'NetworkPoolServer', parentId:poolServer.id,
                                 ipFreeCount: size,ipCount: size]
                newNetworkPool = new NetworkPool(addConfig)
                newNetworkPool.ipRanges = []
                rangeConfig = [cidrIPv6: it.start_address, startIPv6Address: startAddress, endIPv6Address: endAddress,addressCount:size]
                addRange = new NetworkPoolRange(rangeConfig)
                newNetworkPool.ipRanges.add(addRange)
			}
			missingPoolsList.add(newNetworkPool)
		}
		morpheus.network.pool.create(poolServer.id, missingPoolsList).blockingGet()
	}

	void updateMatchedPools(NetworkPoolServer poolServer, List<SyncTask.UpdateItem<NetworkPool,Map>> chunkedUpdateList) {
		List<NetworkPool> poolsToUpdate = []
		chunkedUpdateList?.each { update ->
			NetworkPool existingItem = update.existingItem
            Map network = update.masterItem
			if(existingItem) {
				//update view ?
				def save = false
				def networkIp = network.start_address
				def displayName = network.display
				if(existingItem?.displayName != displayName) {
					existingItem.displayName = displayName
					save = true
				}
				if(existingItem?.cidr != networkIp) {
					existingItem.cidr = networkIp
					save = true
				}
                if(save) {
                    poolsToUpdate << existingItem
                }
            }
        }
		if(poolsToUpdate.size() > 0) {
			morpheus.network.pool.save(poolsToUpdate).blockingGet()
		}
	}
	
	@Override
	ServiceResponse createHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp, NetworkDomain domain, Boolean createARecord, Boolean createPtrRecord) {
		HttpApiClient client = new HttpApiClient();
        InetAddressValidator inetAddressValidator = new InetAddressValidator()
        
        def rpcConfig = getRpcConfig(poolServer)
        
        HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions(ignoreSSL: rpcConfig.ignoreSSL)

        try {
            def tokenResults = login(client,rpcConfig)
            def results = []
            if (tokenResults.success) {
                def hostname = networkPoolIp.hostname
                def token = tokenResults.token.toString()
                requestOptions.headers = [Authorization: "Token ${token}".toString()]
                
                if(domain && hostname && !hostname.endsWith(domain.name))  {
                    hostname = "${hostname}.${domain.name}"
                }

                def apiUrl = cleanServiceUrl(rpcConfig.serviceUrl)
                def apiPath = getServicePath(rpcConfig.serviceUrl) + getIpsPath
                
                if(networkPoolIp.ipAddress) {
                    // Make sure it's a valid IP
                    if (inetAddressValidator.isValidInet4Address(networkPoolIp.ipAddress)) {
                        log.info("A Valid IPv4 Address Entered: ${networkPoolIp.ipAddress}")
                    } else if (inetAddressValidator.isValidInet6Address(networkPoolIp.ipAddress)) {
                        log.info("A Valid IPv6 Address Entered: ${networkPoolIp.ipAddress}")
                    } else {
                        log.error("Invalid IP Address Requested: ${networkPoolIp.ipAddress}", results)
                        return ServiceResponse.error("Invalid IP Address Requested: ${networkPoolIp.ipAddress}")
                    }

                    requestOptions.queryParams = ['address':networkPoolIp.ipAddress]
                    // Check IP Usage
                    results = client.callJsonApi(apiUrl,apiPath,requestOptions,'GET')

                    if (results?.success && !results?.error) {
                        log.info("zzzresults2: ${results}")
                        if (!results?.data.results) {
                            // If Empty, Create the IP
                            apiPath = getServicePath(rpcConfig.serviceUrl) + getIpsPath
                            requestOptions.queryParams = ['address':networkPoolIp.ipAddress]
                            requestOptions.body = JsonOutput.toJson(['address':networkPoolIp.ipAddress,'status':'reserved',"dns_name":hostname])

                            results = client.callJsonApi(apiUrl,apiPath,requestOptions,'POST')

                            log.info("zzzresults: ${results}")

                        } else if (results?.data?.results){
                            // If Reserved
                            def externalId = results.data.results.id
                            apiPath = getServicePath(rpcConfig.serviceUrl) + getIpsPath + externalId + '/'
                            requestOptions.queryParams = [:]
                            requestOptions.body = JsonOutput.toJson(['address':networkPoolIp.ipAddress,'status':'reserved',"dns_name":hostname])

                            results = client.callJsonApi(apiUrl,apiPath,requestOptions,'PUT')
                        } else {
                            log.error("Allocate IP Error: ${e}", e)
                        }
                    } else {
                        rtn.msg = results?.error
                    }
                } else {
                    // Grab next available IP
                    apiPath = getServicePath(rpcConfig.serviceUrl) + networksPath
                    requestOptions.queryParams = [:]
                    requestOptions.body = JsonOutput.toJson(['status':'reserved',"dns_name":hostname])

                    results = client.callJsonApi(apiUrl,apiPath + networkPool.externalId + '/available-ips/',requestOptions,'POST')
                }

                if (results.success && !results.error) {
                    log.info("zzzresults1: ${results}")
                    networkPoolIp.externalId = results.data.id
                    networkPoolIp.ipAddress = results.data.address.tokenize('/')[0]
                }
            }
        } catch(e) {
            log.warn("API Call Failed to allocate IP Address {}",e)
            return ServiceResponse.error("API Call Failed to allocate IP Address",null,networkPoolIp)
        }
        return ServiceResponse.success(networkPoolIp)
	}

	@Override
	ServiceResponse updateHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp) {
		HttpApiClient client = new HttpApiClient()
	}

//	@Override
	ServiceResponse deleteHostRecord(NetworkPool networkPool, NetworkPoolIp poolIp, Boolean deleteAssociatedRecords) {
		HttpApiClient client = new HttpApiClient();
	}

	private ServiceResponse listNetworks(HttpApiClient client, String token, NetworkPoolServer poolServer, Map opts = [:]) {
        def rtn = new ServiceResponse()
        rtn.data = [] // Initialize rtn.data as an empty list

        def rpcConfig = getRpcConfig(poolServer)
        def apiUrl = cleanServiceUrl(rpcConfig.serviceUrl)
        def apiPath = getServicePath(rpcConfig.serviceUrl) + networksPath
        def hasMore = true
        def attempt = 0
        def count = 100
        def start = 0

        log.debug("url: ${apiUrl} path: ${apiPath}")
            
        while(hasMore && attempt < 1000) {
            attempt++
            HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions(ignoreSSL: rpcConfig.ignoreSSL)
            requestOptions.headers = [Authorization: "Token ${token}".toString()]
            requestOptions.queryParams = [limit:count.toString(),offset:start.toString()]

            def results = client.callJsonApi(apiUrl,apiPath,null,null,requestOptions,'GET')

            if(results?.success && results?.error != true) {
                requestOptions.queryParams = [:]
                rtn.success = true
                if(results.data?.results?.size() > 0) {
                    
                    rtn.data += results.data.results

                    if(!results.data.next) {
                        hasMore = false
                    } else {
                        start += count
                    }
     
                } else {
                    hasMore = false
                }
            } else {
                hasMore = false

                if(!rtn.success) {
                    rtn.msg = results.error
                }
            }
        }

        log.debug("List Networks Results: ${rtn}")
        return rtn
	}

	// cacheIpAddressRecords
    void cacheIpAddressRecords(HttpApiClient client, String token, NetworkPoolServer poolServer, Map opts=[:]) {
        morpheus.network.pool.listIdentityProjections(poolServer.id).buffer(50).flatMap { Collection<NetworkPoolIdentityProjection> poolIdents ->
            return morpheus.network.pool.listById(poolIdents.collect{it.id})
        }.flatMap { NetworkPool pool ->
            def listResults = listHostRecords(client,token,poolServer,pool)
            if (listResults.success) {

                List<Map> apiItems = listResults.data
                Observable<NetworkPoolIpIdentityProjection> poolIps = morpheus.network.pool.poolIp.listIdentityProjections(pool.id)
                SyncTask<NetworkPoolIpIdentityProjection, Map, NetworkPoolIp> syncTask = new SyncTask<NetworkPoolIpIdentityProjection, Map, NetworkPoolIp>(poolIps, apiItems)
                return syncTask.addMatchFunction { NetworkPoolIpIdentityProjection ipObject, Map apiItem ->
                    ipObject.externalId == apiItem?.id?.toString()
                }.addMatchFunction { NetworkPoolIpIdentityProjection domainObject, Map apiItem ->
                    domainObject.ipAddress == apiItem?.address.tokenize('/')[0]
                }.onDelete {removeItems ->
                    morpheus.network.pool.poolIp.remove(pool.id, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingIps(pool, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkPoolIpIdentityProjection,Map>> updateItems ->

                    Map<Long, SyncTask.UpdateItemDto<NetworkPoolIpIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.pool.poolIp.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkPoolIp poolIp ->
                        SyncTask.UpdateItemDto<NetworkPoolIpIdentityProjection, Map> matchItem = updateItemMap[poolIp.id]
                        return new SyncTask.UpdateItem<NetworkPoolIp,Map>(existingItem:poolIp, masterItem:matchItem.masterItem)
                    }

                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomain,Map>> updateItems ->
                    updateMatchedIps(updateItems)
                }.observe()
            } else {
                return Single.just(false)
            }
        }.doOnError{ e ->
            log.error("cacheIpRecords error: ${e}", e)
        }.subscribe()

    }

	void addMissingIps(NetworkPool pool, List addList) {
        List<NetworkPoolIp> poolIpsToAdd = addList?.collect { it ->
			def ipAddress = it.address.tokenize('/')[0]
			def types = it.status.value
			def name = it.dns_name
			def ipType = 'assigned'
			if(types?.contains('reserved')) {
				ipType = 'reserved'
			} else if (types?.contains('deprecated')) {
                ipType = 'unmanaged'
            }
			if(!types) {
				ipType = 'used'
			}
			def addConfig = [networkPool: pool, networkPoolRange: pool.ipRanges ? pool.ipRanges.first() : null, ipType: ipType, hostname: name, ipAddress: ipAddress, externalId:it.id]
			def newObj = new NetworkPoolIp(addConfig)
			return newObj

		}
		if(poolIpsToAdd.size() > 0) {
			morpheus.network.pool.poolIp.create(pool, poolIpsToAdd).blockingGet()
		}
	}

	void updateMatchedIps(List<SyncTask.UpdateItem<NetworkPoolIp,Map>> updateList) {
		List<NetworkPoolIp> ipsToUpdate = []
		updateList?.each {  update ->
			NetworkPoolIp existingItem = update.existingItem

			if(existingItem) {
				def hostname = update.dns_name
                def types = update.status.value
				def ipType = 'assigned'
				if(update.types?.contains('reserved')) {
					ipType = 'reserved'
				}
                if(update.types?.contains('deprecated')) {
                    ipType = 'unmanaged'
                }
				if(!update.types) {
					ipType = 'used'
				}
				def save = false
				if(existingItem.ipType != ipType) {
					existingItem.ipType = ipType
					save = true
				}
				if(existingItem.hostname != hostname) {
					existingItem.hostname = hostname
					save = true

				}
				if(save) {
					ipsToUpdate << existingItem
				}
			}
		}
		if(ipsToUpdate.size() > 0) {
			morpheus.network.pool.poolIp.save(ipsToUpdate)
		}
	}


	private ServiceResponse listHostRecords(HttpApiClient client, String token, NetworkPoolServer poolServer,NetworkPool networkPool, Map opts = [:]) {
        def rtn = new ServiceResponse()
        rtn.data = [] // Initialize rtn.data as an empty list
        
        def rpcConfig = getRpcConfig(poolServer)
        def apiUrl = cleanServiceUrl(rpcConfig.serviceUrl)
        def apiPath = getServicePath(rpcConfig.serviceUrl) + getIpsPath
        def hasMore = true
        def attempt = 0
        def count = 100
        def start = 0

        log.debug("url: ${apiUrl} path: ${apiPath}")
            
        while(hasMore && attempt < 1000) {
            attempt++
            HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions(ignoreSSL: rpcConfig.ignoreSSL)
            requestOptions.headers = [Authorization: "Token ${token}".toString()]
            requestOptions.queryParams = [limit:count.toString(),offset:start.toString()]

            def results = client.callJsonApi(apiUrl,apiPath,null,null,requestOptions,'GET')

            if(results?.success && results?.error != true) {
                requestOptions.queryParams = [:]
                rtn.success = true
                if(results.data?.results?.size() > 0) {
                    
                    rtn.data += results.data.results

                    if(!results.data.next) {
                        hasMore = false
                    } else {
                        start += count
                    }
     
                } else {
                    hasMore = false
                }
            } else {
                hasMore = false

                if(!rtn.success) {
                    rtn.msg = results.error
                }
            }
        }

        log.debug("listHostRecords Results: ${rtn}")
        return rtn
	}

	ServiceResponse testNetworkPoolServer(HttpApiClient client, String token, NetworkPoolServer poolServer) {
		def rtn = new ServiceResponse()
		try {
			def opts = [doPaging:false, maxResults:1]
			def networkList = listNetworks(client, token, poolServer, opts)
			rtn.success = networkList.success
			rtn.data = [:]
			if(!networkList.success) {
				rtn.msg = 'error connecting to NetBox'
			}
		} catch(e) {
			rtn.success = false
			log.error("test network pool server error: ${e}", e)
		}
		return rtn
	}

    /**
     * Periodically called to refresh and sync data coming from the relevant integration. Most integration providers
     * provide a method like this that is called periodically (typically 5 - 10 minutes). DNS Sync operates on a 10min
     * cycle by default. Useful for caching Host Records created outside of Morpheus.
     * @param poolServer The Integration Object contains all the saved information regarding configuration of the IPAM Provider.
     */
    @Override
    void refresh(NetworkPoolServer poolServer) {
        log.debug("refreshNetworkPoolServer: {}", poolServer.dump())
        HttpApiClient netboxClient = new HttpApiClient()
        netboxClient.throttleRate = poolServer.serviceThrottleRate
        def tokenResults
        def rpcConfig = getRpcConfig(poolServer)
        try {
            def apiUrl = poolServer.serviceUrl
            def apiUrlObj = new URL(apiUrl)
            def apiHost = apiUrlObj.host
            def apiPort = apiUrlObj.port > 0 ? apiUrlObj.port : (apiUrlObj?.protocol?.toLowerCase() == 'https' ? 443 : 80)
            def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, false, true, null)
            log.debug("online: {} - {}", apiHost, hostOnline)
            def testResults
            // Promise

            if(hostOnline) {
                tokenResults = login(netboxClient,rpcConfig)
                if(tokenResults.success) {
                    testResults = testNetworkPoolServer(netboxClient,tokenResults.token as String,poolServer) as ServiceResponse<Map>
                    if(!testResults?.success) {
                        morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'error calling NetBox').blockingGet()
                    } else {
                        morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.syncing).blockingGet()
                    }
                } else {
                    morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'error authenticating with NetBox').blockingGet()
                }
            } else {
                morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.error, 'NetBox api not reachable')
            }
            Date now = new Date()
            if(testResults?.success) {
                String token = tokenResults?.token as String
                cacheNetworks(netboxClient,token,poolServer)
                if(poolServer?.configMap?.inventoryExisting) {
                    //cacheIpAddressRecords(netboxClient,token,poolServer)
                }
                log.info("Sync Completed in ${new Date().time - now.time}ms")
                morpheus.network.updateNetworkPoolServerStatus(poolServer, AccountIntegration.Status.ok).subscribe().dispose()
            }
        } catch(e) {
            log.error("refreshNetworkPoolServer error: ${e}", e)
        } finally {
            if(tokenResults?.success) {
                logout(netboxClient,rpcConfig,tokenResults.token as String)
            }
            netboxClient.shutdownClient()
        }
    }

	/**
	 * An IPAM Provider can register pool types for display and capability information when syncing IPAM Pools
	 * @return a List of {@link NetworkPoolType} to be loaded into the Morpheus database.
	 */
	Collection<NetworkPoolType> getNetworkPoolTypes() {
		return [
			new NetworkPoolType(code:'netbox', name:'NetBox', creatable:false, description:'NetBox', rangeSupportsCidr: false),
			new NetworkPoolType(code:'netboxipv6', name:'NetBox IPv6', creatable:false, description:'NetBox IPv6', rangeSupportsCidr: true, ipv6Pool:true)
		]
	}

	/**
	 * Provide custom configuration options when creating a new {@link AccountIntegration}
	 * @return a List of OptionType
	 */
	@Override
	List<OptionType> getIntegrationOptionTypes() {
		return [
				new OptionType(code: 'netbox.serviceUrl', name: 'Service URL', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUrl', fieldLabel: 'API Url', fieldContext: 'domain', placeHolder: 'https://x.x.x.x/wapi/v2.2.1', helpBlock: 'Warning! Using HTTP URLS are insecure and not recommended.', displayOrder: 0, required:true),
				new OptionType(code: 'netbox.credentials', name: 'Credentials', inputType: OptionType.InputType.CREDENTIAL, fieldName: 'type', fieldLabel: 'Credentials', fieldContext: 'credential', required: true, displayOrder: 1, defaultValue: 'local',optionSource: 'credentials',config: '{"credentialTypes":["username-password"]}'),

				new OptionType(code: 'netbox.serviceUsername', name: 'Service Username', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUsername', fieldLabel: 'Username', fieldContext: 'domain', displayOrder: 2,localCredential: true, required: true),
				new OptionType(code: 'netbox.servicePassword', name: 'Service Password', inputType: OptionType.InputType.PASSWORD, fieldName: 'servicePassword', fieldLabel: 'Password', fieldContext: 'domain', displayOrder: 3,localCredential: true, required: true),
				new OptionType(code: 'netbox.throttleRate', name: 'Throttle Rate', inputType: OptionType.InputType.NUMBER, defaultValue: 0, fieldName: 'serviceThrottleRate', fieldLabel: 'Throttle Rate', fieldContext: 'domain', displayOrder: 4),
				new OptionType(code: 'netbox.ignoreSsl', name: 'Ignore SSL', inputType: OptionType.InputType.CHECKBOX, defaultValue: 0, fieldName: 'ignoreSsl', fieldLabel: 'Disable SSL SNI Verification', fieldContext: 'domain', displayOrder: 5),
				new OptionType(code: 'netbox.inventoryExisting', name: 'Inventory Existing', inputType: OptionType.InputType.CHECKBOX, defaultValue: 0, fieldName: 'inventoryExisting', fieldLabel: 'Inventory Existing', fieldContext: 'config', displayOrder: 6)
		]
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"netbox.svg", darkPath: "netbox.svg")
	}

    def login(HttpApiClient client, rpcConfig) {
        def rtn = [success:false]
        try {
            HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions(ignoreSSL: rpcConfig.ignoreSSL)
            requestOptions.headers = ['content-type':'application/json']
            requestOptions.body = JsonOutput.toJson([username:rpcConfig.username, password:rpcConfig.password])

            def apiUrl = cleanServiceUrl(rpcConfig.serviceUrl)
            def apiPath = getServicePath(rpcConfig.serviceUrl) + authPath

            def results = client.callJsonApi(apiUrl,apiPath,requestOptions,'POST')
            if(results?.success && results?.error != true) {
                log.debug("login: ${results}")
                rtn.token = results.data?.key?.trim()
                rtn.success = true
            } else {
                //error
            }
        } catch(e) {
            log.error("getToken error: ${e}", e)
        }
        return rtn
    }

    void logout(HttpApiClient client, rpcConfig, String token) {
        try {
            def apiUrl = cleanServiceUrl(rpcConfig.serviceUrl)
            def apiPath = getServicePath(rpcConfig.serviceUrl) + 'logout'
            HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions(ignoreSSL: rpcConfig.ignoreSSL)
            requestOptions.headers = [Authorization: "Token ${token}".toString()]
            client.callJsonApi(apiUrl,apiPath,requestOptions,"GET")
        } catch(e) {
            log.error("logout error: ${e}", e)
        }
    }

    private getRpcConfig(NetworkPoolServer poolServer) {
        return [
            username:poolServer.credentialData?.username ?: poolServer.serviceUsername,
            password:poolServer.credentialData?.password ?: poolServer.servicePassword,
            serviceUrl:poolServer.serviceUrl,
            ignoreSSL: poolServer.ignoreSsl
        ]
    }

	private static String cleanServiceUrl(String url) {
		def rtn = url
		def slashIndex = rtn?.indexOf('/', 9)
		if(slashIndex > 9)
			rtn = rtn.substring(0, slashIndex)
		return rtn
	}

	private static String getServicePath(String url) {
		def rtn = '/'
		def slashIndex = url?.indexOf('/', 9)
		if(slashIndex > 9)
			rtn = url.substring(slashIndex)
		if(rtn?.endsWith('/'))
			return rtn.substring(0, rtn.lastIndexOf("/"));
		return rtn
	}
}
