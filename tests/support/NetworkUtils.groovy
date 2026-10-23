package hubitat.helper

/** Stand-in for hubitat.helper.NetworkUtils: the hub's mDNS cache, which tests fill in. */
class NetworkUtils {
    static Map<String, Map> mdns = [:]
    static Map<String, Map> getRawMDNSEndpointsByMACForServiceType(String serviceType) { mdns }
}
