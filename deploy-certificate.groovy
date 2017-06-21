#!/usr/bin/env groovy
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Grab(group='org.wildfly.core', module='wildfly-embedded', version='2.2.1.Final')
@Grab(group='org.wildfly.security', module='wildfly-security-manager', version='1.1.2.Final')
@Grab(group='org.wildfly.core', module='wildfly-cli', version='3.0.0.Beta23')
import org.jboss.as.cli.scriptsupport.*

@Grab(group='org.springframework.retry', module='spring-retry', version='1.2.0.RELEASE')
import org.springframework.retry.*
import org.springframework.retry.support.*
import org.springframework.retry.backoff.*
import org.springframework.retry.policy.*

@Grab(group='commons-io', module='commons-io', version='2.5')
import org.apache.commons.io.*

@Grab(group='com.google.guava', module='guava', version='22.0')
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList

@Grab(group='org.apache.commons', module='commons-collections4', version='4.0')
import org.apache.commons.collections4.CollectionUtils

final DEFAULT_HOST = "localhost"
final DEFAULT_PORT = "9990"
final OCTOPUS_SSL_REALM = "octopus-ssl-realm"
final DEFAULT_PROTOCOL = "remote+http"

/*
    Define and parse the command line arguments
 */
def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    c longOpt: 'controller', args: 1, argName: 'controller', 'WildFly controller'
    d longOpt: 'port', args: 1, argName: 'port', type: Number.class, 'Wildfly management port'
    e longOpt: 'protocol', args: 1, argName: 'protocol', 'Wildfly management protocol i.e. remote+https'
    u longOpt: 'user', args: 1, argName: 'username', required: true, 'WildFly management username'
    p longOpt: 'password', args: 1, argName: 'password', required: true, 'WildFly management password'
    k longOpt: 'keystore-file', args: 1, argName: 'path to keystore', required: true, 'Java keystore file'
    q longOpt: 'keystore-password', args: 1, argName: 'application name', required: true, 'Keystore password'
    m longOpt: 'management-interface', 'Apply certificate to the Management interface'
    n longOpt: 'management-port', args: 1, argName: 'port', type: Number.class, 'Wildfly management ssl port'
    o longOpt: 'profiles', args: 1, argName: 'profiles', type: String.class, 'Profiles to add ssl to'
    q longOpt: 'hosts', args: 1, argName: 'hosts', 'Hosts to add the SSL configuration to'
    r longOpt: 'no-profiles', 'Don\'t update any profiles'
    s longOpt: 'no-hosts', 'Don\'t update any hosts'
    t longOpt: 'no-restart', 'Don\'t restart any hosts'
}

def options = cli.parse(args)
if (!options) {
    return
}

if (options.h) {
    cli.usage()
    return
}

if (!new File(options.'keystore-file').exists()) {
    println "The file ${options.'keystore-file'} does not exist"
    return
}

/*
    Connect to the server
 */
def jbossCli = CLI.newInstance()

/*
   Build up a retry template
 */
def retryTemplate = new RetryTemplate()
def retryPolicy = new SimpleRetryPolicy(5)
retryTemplate.setRetryPolicy(retryPolicy)

def backOffPolicy = new ExponentialBackOffPolicy()
backOffPolicy.setInitialInterval(1000L)
retryTemplate.setBackOffPolicy(backOffPolicy)

/*
    Adds the keystore details to a security realm
 */
def addKeystoreToRealm = { host, realm ->
    def hostPrefix = host ? "/host=${host}" : ""
    def hostName = host ?: "Standalone"

    /*
        Add the server identity to the web interface
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add server identity for ${hostName}.")

            def keystoreFile = options.'keystore-file'
                    .replaceAll('\\\\', '\\\\\\\\')
                    .replaceAll('"', '\\\\"')
            def keystorePassword = options.'keystore-password'
                    .replaceAll('\\\\', '\\\\\\\\')
                    .replaceAll('"', '\\\\"')

            def existsResult = jbossCli.cmd("${hostPrefix}/core-service=management/security-realm=${realm}/server-identity=ssl:read-resource")
            if (existsResult.success) {
                /*
                    See if we have actual changes to make
                 */
                def existingAlias = existsResult.response.get("result").get("alias").asString()
                def existingKeystorePath = existsResult.response.get("result").get("keystore-path").asString()
                def existingKeystorePassword = existsResult.response.get("result").get("keystore-password").asString()

                if ("octopus".equals(existingAlias) &&
                        keystoreFile.equals(existingKeystorePath) &&
                        keystorePassword.equals(existingKeystorePassword)) {
                    println "No changes need to be made to to add server identity for ${hostName}"
                    return
                }

                /*
                    Remove the ssl identity so it can be recreated
                 */
                def removeResult = jbossCli.cmd("${hostPrefix}/core-service=management/security-realm=${realm}/server-identity=ssl:remove")
                if (!removeResult.success) {
                    throw new Exception("Failed to remove server identity for ${hostName}. ${removeResult.response.toString()}")
                }
            }

            def command = "${hostPrefix}/core-service=management/security-realm=${realm}/server-identity=ssl/:add(" +
                    "alias=\"octopus\", " +
                    "keystore-path=\"${keystoreFile}\", " +
                    "keystore-password=\"${keystorePassword}\")"

            def addResult = jbossCli.cmd(command)
            if (!addResult.success) {
                throw new Exception("Failed to create server identity for ${hostName}. ${addResult.response.toString()}")
            }
        }
    })
}

/*
    Adds ssl details to a host for the default realm
 */
def addSslToHost = { host ->
    def hostPrefix = host ? "/host=${host}" : ""
    def hostName = host ?: "Standalone"

    /*
        Add the security realm
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add security realm for ${hostName}.")

            def existsResult = jbossCli.cmd("${hostPrefix}/core-service=management/security-realm=${OCTOPUS_SSL_REALM}:read-resource")
            if (!existsResult.success) {
                def addRealm = jbossCli.cmd("${hostPrefix}/core-service=management/security-realm=${OCTOPUS_SSL_REALM}:add()")
                if (!addRealm.success) {
                    throw new Exception("Failed to add security realm for ${hostName}. ${addRealm.response.toString()}")
                }
            }
        }
    })

    addKeystoreToRealm(host, OCTOPUS_SSL_REALM)
}

/*
    Gets the names of the undertow servers
 */
def getUndertowServers = { profile ->
    def profilePrefix = profile ? "/profile=${profile}" : ""
    def profileName = profile ?: "Standalone"

    def hostResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get undertow servers for ${profileName}.")

            def result = jbossCli.cmd("${profilePrefix}/subsystem=undertow/server=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to read undertow servers for ${profileName}. ${result.response.toString()}")
            }
            return result
        }
    })

    def servers = hostResult.response.get("result").asList().collect {
        it.get("address").asPropertyList().findAll {
            it.name.equals("server")
        }.collect {
            it.value.asString()
        }
    }.flatten()

    return servers
}

/*
    Restarts a host
 */
def restartServer = { host ->
    if (options.'no-restart') {
        return
    }

    def hostPrefix = host ? "/host=${host}" : ""
    def hostName = host ?: "Standalone"

    /*
        Restart the server
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to restart server ${hostName}.")

            def restartResult = jbossCli.cmd("${hostPrefix}/:shutdown(restart=true)")
            if (!restartResult.success) {
                throw new Exception("Failed to restart the server. ${restartResult.response.toString()}")
            }
        }
    })
}

/*
    Gets the default interface of a socket binding group
 */
def getDefaultInterface = { socketGroup ->
    def defaultInterfaceResult = jbossCli.cmd("/socket-binding-group=${socketGroup}:read-resource")
    if (!defaultInterfaceResult.success) {
        throw new Exception("Failed to validate socket binding. ${defaultInterfaceResult.response.toString()}")
    }

    def defaultInterface = defaultInterfaceResult.response.get("result").get("default-interface").asString()

    return defaultInterface
}

/*
    Ensures that the https socket binding exists for the given group
 */
def validateSocketBinding = { socketGroup ->

    def defaultInterface = getDefaultInterface(socketGroup)

    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to validate socket binding for group ${socketGroup}.")

            def result = jbossCli.cmd("/socket-binding-group=${socketGroup}/socket-binding=https:read-resource")
            if (!result.success) {
                throw new Exception("Failed to validate socket binding. ${result.response.toString()}")
            } else {
                def bindingInterface = result.response.get("result").get("interface").asString()

                def isUndefined = !bindingInterface
                def isPublicPort = "public".equals(bindingInterface)
                def defaultIsPublic = "public".equals(defaultInterface)

                if (isPublicPort || (isUndefined && defaultIsPublic)) {
                    throw new Exception("https socket binding was not for the public interface. ${result.response.toString()}")
                }
            }
            return result
        }
    })
}

/*
    Ensures that the management-https socket binding exists
 */
def validateManagementSocketBinding = { socketGroup ->
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to validate management socket binding.")

            def defaultInterface = getDefaultInterface(socketGroup)

            def result = jbossCli.cmd("/socket-binding-group=${socketGroup}/socket-binding=management-https:read-resource")
            if (!result.success) {
                throw new Exception("Failed to validate socket binding. ${result.response.toString()}")
            } else {
                def bindingInterface = result.response.get("result").get("interface").asString()

                def isUndefined = !bindingInterface
                def isManagementPort = "management".equals(bindingInterface)
                def defaultIsManagement = "management".equals(defaultInterface)

                if (!(isManagementPort || (isUndefined && defaultIsManagement))) {
                    throw new Exception("management-https socket binding was not for the management interface. Binding was ${bindingInterface} and default was ${defaultInterface}. ${result.response.toString()}")
                }
            }
            return result
        }
    })
}

/*
    Returns all the socket binding groups used by the servers maintained
    by the supplied host
 */
def getSocketBindingsForHost = { host ->
    def result = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get host socket groups for host ${host}.")

            def result = jbossCli.cmd("/host=${host}/server=*/socket-binding-group=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to get socket groups for host ${host}. ${result.response.toString()}")
            }

            return result
        }
    })

    def socketGroups = result.response.get("result").asList().collect {
        it.get("result").get("name").asString()
    }

    println "Found socket groups ${socketGroups} for host ${host}"

    return socketGroups
}

/*
    Returns the name of the socket binding group used in the standalone instance
 */
def getSocketBindingsForStandalone = {
    def result = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get socket group for standalone.")

            def result = jbossCli.cmd("/socket-binding-group=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to get socket groups for standalone. ${result.response.toString()}")
            }

            return result
        }
    })

    def socketGroup = result.response.get("result").asList().collect {
        it.get("result").get("name").asString()
    }.first()

    println "Found socket group ${socketGroup} for standalone"

    return socketGroup
}

/*
    Sets up the https socket binding for a profile
 */
def addServerIdentity = { profile ->

    def profilePrefix = profile ? "/profile=${profile}" : ""
    def profileName = profile ?: "Standalone"

    def servers = getUndertowServers(profile)

    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {

            servers.forEach {
                println("Attempt ${context.retryCount + 1} to set the https listener for server ${it} in ${profileName}.")

                def existsResult = jbossCli.cmd("${profilePrefix}/subsystem=undertow/server=${it}/https-listener=https:read-resource")
                if (!existsResult.success) {
                    def realmResult = jbossCli.cmd("${profilePrefix}/subsystem=undertow/server=${it}/https-listener=https/:add(" +
                            "socket-binding=https, " +
                            "security-realm=${OCTOPUS_SSL_REALM})")
                    if (!realmResult.success) {
                        throw new Exception("Failed to set the https realm for ${profileName}. ${realmResult.response.toString()}")
                    }
                } else {
                    def bindingResult = jbossCli.cmd("${profilePrefix}/subsystem=undertow/server=${it}/https-listener=https/:write-attribute(" +
                            "name=socket-binding, " + "" +
                            "value=https)")
                    if (!bindingResult.success) {
                        throw new Exception("Failed to set the socket binding for ${profileName}. ${bindingResult.response.toString()}")
                    }
                    def realmResult = jbossCli.cmd("${profilePrefix}/subsystem=undertow/server=${it}/https-listener=https/:write-attribute(" +
                            "name=security-realm, " +
                            "value=${OCTOPUS_SSL_REALM})")
                    if (!realmResult.success) {
                        throw new Exception("Failed to set the security realm for server ${it} in ${profileName}. ${realmResult.response.toString()}")
                    }
                }
            }
        }
    })
}

/*
    Gets the name of the management realm
 */
def getManagementRealm = {host ->
    def hostPrefix = host ? "/host=${host}" : ""
    def hostName = host ?: "Standalone"

    def hostResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get security realm for ${hostName}.")

            def result = jbossCli.cmd("${hostPrefix}/core-service=management/management-interface=http-interface:read-attribute(name=security-realm)")
            if (!result.success) {
                throw new Exception("Failed to read security realm information for ${hostName}. ${result.response.toString()}")
            }
            return result
        }
    })

    return hostResult.response.get("result").asString()
}

/*
    Configures the secure socket for the management interface
 */
def configureManagementDomain = { host ->
    def hostPrefix = "/host=${host}"

    /*
        Bind the management interface to the ssl port
    */
    return retryTemplate.execute(new RetryCallback<Boolean, Exception>() {
        @Override
        Boolean doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to change management socket binding for ${host}.")

            /*
                Slave instances may not have a http interface, so check first
             */
            def httpInterfaceResult = jbossCli.cmd("${hostPrefix}/core-service=management/management-interface=http-interface:read-resource")
            if (httpInterfaceResult.success) {
                addKeystoreToRealm(host, getManagementRealm(host))

                /*
                    Domain configs set the secure socket directly
                 */
                def socketBindingResult = jbossCli.cmd("${hostPrefix}/core-service=management/management-interface=http-interface:write-attribute(" +
                        "name=secure-port, " +
                        "value=${options.'management-port'}")
                if (!socketBindingResult.success) {
                    throw new Exception("Failed to change management socket binding for ${host}. ${socketBindingResult.response.toString()}")
                }

                return true

            }

            println "${hostName} has no http management interface, so skipping"
            return false
        }
    })
}

/*
    Configures the socket group binding for the standalone management interface
 */
def configureManagementStandalone = { socketGroup ->
    validateManagementSocketBinding(socketGroup)
    addKeystoreToRealm(null, getManagementRealm(null))

    /*
        Bind the management interface to the ssl binding group
    */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to change management socket binding for standalone.")

            /*
                We may not have a management socket binding for domain configs
             */
            def socketBindingExists = jbossCli.cmd("/core-service=management/management-interface=http-interface:read-attribute(" +
                    "name=secure-socket-binding")

            if (socketBindingExists.success) {
                def socketBindingResult = jbossCli.cmd("/core-service=management/management-interface=http-interface:write-attribute(" +
                        "name=secure-socket-binding, " +
                        "value=management-https")
                if (!socketBindingResult.success) {
                    throw new Exception("Failed to change management socket binding for standalone. ${socketBindingResult.response.toString()}")
                }
            }
        }
    })
}

/*
    Get the domain master host
 */
def getMasterHosts = {
    def hostResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get master host.")

            def result = jbossCli.cmd("/host=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to read host information. ${result.response.toString()}")
            }
            return result
        }
    })

    def hosts = hostResult.response.get("result").asList().findAll {
        it.get("result").get("master").asBoolean()
    }.collect {
        it.get("result").get("name").asString()
    }

    println "Found domain master host \"${hosts.first()}\""

    return hosts.first()
}

/*
    Get the domain slave hosts that we will be working with
 */
def getSlaveHosts = {
    def hostResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get slave hosts.")

            def result = jbossCli.cmd("/host=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to read host information. ${result.response.toString()}")
            }
            return result
        }
    })

    def hosts = hostResult.response.get("result").asList().findAll {
        !it.get("result").get("master").asBoolean()
    }.collect {
        it.get("result").get("name").asString()
    }

    println "Found domain slave hosts \"${hosts}\""

    if (options.hosts) {
        def suppliedHosts = ImmutableList.copyOf(Splitter.on(',')
                .trimResults()
                .omitEmptyStrings()
                .split(options.hosts))

        def invalid = CollectionUtils.subtract(suppliedHosts, hosts)

        if (!invalid.empty) {
            throw new Exception("The hosts ${invalid} did not match any hosts ${hosts} in the domain config")
        }

        return suppliedHosts
    }

    return hosts
}

/*
    Get the profiles that we will be working with
 */
def getProfiles = {
    if (options.'no-profiles') {
        return []
    }

    def profileResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get profiles.")

            def result = jbossCli.cmd("/profile=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to read profile information. ${result.response.toString()}")
            }
            return result
        }
    })

    def profiles = profileResult.response.get("result").asList().collect {
        it.get("result").get("name").asString()
    }

    if (options.profiles) {
        def suppliedProfiles = ImmutableList.copyOf(Splitter.on(',')
                .trimResults()
                .omitEmptyStrings()
                .split(options.profiles))

        def invalid = CollectionUtils.subtract(suppliedProfiles, profiles)

        if (!invalid.empty) {
            throw new Exception("The profiles ${invalid} did not match any profiles ${profiles} in the domain config")
        }

        return suppliedProfiles
    }

    return profiles
}

retryTemplate.execute(new RetryCallback<Void, Exception>() {
    @Override
    Void doWithRetry(RetryContext context) throws Exception {

        println("Attempt ${context.retryCount + 1} to connect.")

        jbossCli.connect(
                options.protocol ?: DEFAULT_PROTOCOL,
                options.controller ?: DEFAULT_HOST,
                Integer.parseInt(options.port ?: DEFAULT_PORT),
                options.user,
                options.password.toCharArray())
        return null
    }
})

/*
    Backup the configuration
 */
retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
    @Override
    CLI.Result doWithRetry(RetryContext context) throws Exception {
        println("Attempt ${context.retryCount + 1} to snapshot the configuration.")

        def snapshotResult = jbossCli.cmd("/:take-snapshot")
        if (!snapshotResult.success) {
            throw new Exception("Failed to snapshot the configuration. ${snapshotResult.response.toString()}")
        }
    }
})

if (jbossCli.getCommandContext().isDomainMode()) {

    def slaveHosts = getSlaveHosts()
    def masterHost = getMasterHosts()

    def profiles = getProfiles()

    if (options.'management-interface') {
        if (!options.'management-port') {
            println "You must define a management-port when configuring the management interface in a domain environment"
            return
        }

        /*
            Find the first host to have a http management interface. This will be the domain controller
         */
        configureManagementDomain(masterHost)
        restartServer(masterHost)
    } else {
        slaveHosts.forEach {
            def serverGroups = getSocketBindingsForHost(it)
            serverGroups.forEach {
                validateSocketBinding(it)
            }
            addSslToHost(it)
        }

        profiles.forEach {
            addServerIdentity(it)
        }

        slaveHosts.forEach {
            restartServer(it)
        }
    }
} else {
    if (options.'management-interface') {
        configureManagementStandalone(getSocketBindingsForStandalone())
    } else {
        /*
            Configure the core-management subsystem
         */
        addSslToHost(null)

        /*
            Bind the web interface to the ssl security realm
         */
        validateSocketBinding(getSocketBindingsForStandalone())
        addServerIdentity(null)
    }

    /*
        Restart the server
     */
    restartServer(null)
}

/*
    Exiting like this can have consequences if the code is run from a Java app. But
    for an Octopus Deploy script it is ok, and is actually necessary to properly exit
    the script.
 */
System.exit(0)