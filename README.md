## Morpheus NetBox Plugin

This is the official Morpheus plugin for interacting with NetBox functionality. Automating functions with regards to IPAM services as well as DNS Services. This plugin syncs in configured subnets/pools, dns zones, dns resource records, and ip records for viewing directly in morpheus as well as manipulating when necessary. It also provides a way to attach a subnet/pool to a cloud network and automate the assignment and release of ipaddress resources for the workload being requested.

### Building

This is a Morpheus plugin that leverages the `morpheus-plugin-core` which can be referenced by visiting [https://developer.morpheusdata.com](https://developer.morpheusdata.com). It is a groovy plugin designed to be uploaded into a Morpheus environment via the `Administration -> Integrations -> Plugins` section. To build this product from scratch simply run the shadowJar gradle task on java 11:

```bash
./gradlew shadowJar
```

A jar will be produced in the `build/lib` folder that can be uploaded into a Morpheus environment.


### Configuring

Once the plugin is loaded in the environment. Efficient IP Becomes available in `Infrastructure -> Network -> Services`.

When adding the integration simply enter the URL of the NetBox Server (no path is needed just the root url) and the credentials with sufficient privileges to talk to the API.
