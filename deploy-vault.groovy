#!/usr/bin/env groovy
@Grab(group='org.wildfly.core', module='wildfly-embedded', version='2.2.1.Final')
@Grab(group='org.wildfly.security', module='wildfly-security-manager', version='1.1.2.Final')
@Grab(group='org.wildfly.core', module='wildfly-cli', version='3.0.0.Beta23')
import org.jboss.as.cli.scriptsupport.*

@Grab(group='org.springframework.retry', module='spring-retry', version='1.2.0.RELEASE')
import org.springframework.retry.*
import org.springframework.retry.support.*
import org.springframework.retry.backoff.*
import org.springframework.retry.policy.*

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
    f longOpt: 'protocol', args: 1, argName: 'protocol', 'Wildfly management protocol i.e. remote+https'
    u longOpt: 'user', args: 1, argName: 'username', required: true, 'WildFly management username'
    p longOpt: 'password', args: 1, argName: 'password', required: true, 'WildFly management password'
    k longOpt: 'keystore-file', args: 1, argName: 'keystore-file', required: true, 'Java keystore file'
    q longOpt: 'keystore-password', args: 1, argName: 'keystore-password', required: true, 'Keystore password'
    i longOpt: 'iteration', args: 1, argName: 'iteration', type: Number.class, 'Iteration count'
    s longOpt: 'salt', args: 1, argName: 'salt', '8 character salt'
    b longOpt: 'vault-block', args: 1, argName: 'vault-block', 'Vault block'
    e longOpt: 'enc-dir', args: 1, argName: 'enc-dir', required: true, 'Directory containing encrypted files'
    v longOpt: 'alias', args: 1, argName: 'alias', 'Vault keystore alias'
    t longOpt: 'no-restart', 'Don\'t restart any hosts'
    q longOpt: 'hosts', args: 1, argName: 'hosts', 'Hosts to add the SSL configuration to'
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
    Adds ssl details to a host for the default realm
 */
def addVaultToHost = { host ->
    def hostPrefix = host ? "/host=${host}" : ""
    def hostName = host ?: "Standalone"

    /*
        Add the security realm
     */
    return retryTemplate.execute(new RetryCallback<Boolean, Exception>() {
        @Override
        Boolean doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add vault ${hostName}.")


            def vaultExists = jbossCli.cmd("${hostPrefix}/core-service=vault:read-resource")

            if (vaultExists.success) {
                def updateRequired = false
                def properties = vaultExists.response.get("result").asPropertyList()

                if (properties.find {"KEYSTORE_URL".equals(it.name) && ${options.'keystore-file'}.equals(it.vaule)} ||
                        properties.find {"KEYSTORE_PASSWORD".equals(it.name) && ${options.'keystore-password'}.equals(it.vaule)} ||
                        properties.find {"KEYSTORE_ALIAS".equals(it.name) && ${options.alias ?: "vault"}.equals(it.vaule)} ||
                        properties.find {"SALT".equals(it.name) && ${options.salt ?: "12345678"}.equals(it.vaule)} ||
                        properties.find {"ITERATION_COUNT".equals(it.name) && ${options.iteration ?: "50"}.equals(it.vaule)} ||
                        properties.find {"ENC_FILE_DIR".equals(it.name) &&${options.'enc-dir'}.equals(it.vaule)}) {
                    updateRequired = true
                    def removeResult = jbossCli.cmd("${hostPrefix}/core-service=vault:remove")
                    if (!removeResult.success) {
                        throw new Exception("Failed to remove vault for ${hostName}. ${removeResult.response.toString()}")
                    }
                }
            } else {
                updateRequired = true
            }

            if (updateRequired) {
                def keystoreFile = options.'keystore-file'
                        .replaceAll('\\\\', '\\\\\\\\')
                        .replaceAll('"', '\\\\"')
                def keystorePassword = options.'keystore-password'
                        .replaceAll('\\\\', '\\\\\\\\')
                        .replaceAll('"', '\\\\"')

                def addVault = jbossCli.cmd("${hostPrefix}/core-service=vault:add(vault-options={" +
                        "\"KEYSTORE_URL\" => \"${keystoreFile}\", " +
                        "\"KEYSTORE_PASSWORD\" => \"${keystorePassword}\", " +
                        "\"KEYSTORE_ALIAS\" => \"${options.alias ?: "vault"}\", " +
                        "\"SALT\" => \"${options.salt ?: "12345678"}\", " +
                        "\"ITERATION_COUNT\" => \"${options.iteration ?: "50"}\", " +
                        "\"ENC_FILE_DIR\" => \"${options.'enc-dir'}/\"})")

                if (!addVault.success) {
                    throw new Exception("Failed to add vault for ${hostName}. ${addVault.response.toString()}")
                }

                return true
            }

            println "No updates to vault configuration required"

            return false
        }
    })
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
    Get the domain master host
 */
def getHosts = {
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

    def hosts = hostResult.response.get("result").asList().collect {
        it.get("result").get("name").asString()
    }

    println "Found domain hosts \"${hosts}\""

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

    def hosts = getHosts()

    hosts.forEach {
        addVaultToHost(it)
    }

    hosts.forEach {
        restartServer(it)
    }
} else {
    addVaultToHost(null)
    restartServer(null)
}

/*
    Exiting like this can have consequences if the code is run from a Java app. But
    for an Octopus Deploy script it is ok, and is actually necessary to properly exit
    the script.
 */
System.exit(0)