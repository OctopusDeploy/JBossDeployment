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

@Grab(group='commons-io', module='commons-io', version='2.5')
import org.apache.commons.io.*

@Grab(group='com.google.guava', module='guava', version='22.0')
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList

@Grab(group='org.apache.commons', module='commons-collections4', version='4.0')
import org.apache.commons.collections4.CollectionUtils

final DEFAULT_HOST = "localhost"
final DEFAULT_PORT = "9990"
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
    a longOpt: 'application', args: 1, argName: 'path to artifact', required: true, 'Application to be deployed'
    n longOpt: 'name', args: 1, argName: 'application name', 'Application name'
    e longOpt: 'disabled', 'Disable the application'
    s longOpt: 'enabled-server-group', args: 1, argName: 'server group', 'Server group to enable in'
    x longOpt: 'disabled-server-group', args: 1, argName: 'server group', 'Server group to disable in'
}

def options = cli.parse(args)
if (!options) {
    return
}

if (options.h) {
    cli.usage()
    return
}

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
    Connect to the server
 */
def jbossCli = CLI.newInstance()

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

/*
    Upload the package
 */
def packageName = FilenameUtils.getName(options.application)
def name = options.name ? "--name=${options.name}" : "--name=${packageName}"

if (jbossCli.getCommandContext().isDomainMode()) {

    /*
        If you push a new deployment, and that deployment is assigned but disabled
        in a server group, then it can end up enabled after the push.

        Here we get all the server groups and find out where the deployment is assigned
        and disabled. If this server group is not listed already in either the
        enabled-server-group or disabled-server-group options, it is added to the
        additionalDisabledServerGroups variable.

        See https://issues.jboss.org/browse/WFLY-8909 for details.
     */
    def serverGroupResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to get server groups.")

            def result = jbossCli.cmd("/server-group=*:read-resource")
            if (!result.success) {
                throw new Exception("Failed to upload package. ${result.response.toString()}")
            }
            return result
        }
    })

    /*
        Convert the DMR result into a flat list of server groups
     */
    def serverGroups = serverGroupResult.response.get("result").asList().collect {
        it.get("address").asList().collect {
            it.get("server-group").asString()
        }
    }.flatten()

    /*
        Get a list of all the server groups that were supplied by the command line
     */
    def suppliedServerGroups = ImmutableList.copyOf(Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split((options.'enabled-server-group' ?: '') + "," + (options.'disabled-server-group' ?: '')))

    def missing = CollectionUtils.subtract(serverGroups, suppliedServerGroups)

    /*
        Find server groups where we need to disable the deployment again
     */
    def additionalDisabledServerGroups = missing.inject("", { sum, value ->
        def readResourceResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
            @Override
            CLI.Result doWithRetry(RetryContext context) throws Exception {
                println("Attempt ${context.retryCount + 1} to get deployment info for ${value}.")

                return jbossCli.cmd("/server-group=${value}/deployment=${packageName}:read-resource")
            }
        })

        if (readResourceResult.success) {
            if (!readResourceResult.response.get("result").get("enabled").asBoolean()) {
                println "Adding ${value} to the list of disabled deployments to prevent accidential enablement of the deployment after the content is updated"
                sum += "," + value
            }
        }

        return sum
    })

    /*
        Upload the new content
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to upload package.")

            def result = jbossCli.cmd("deploy --force ${name} ${options.application}")
            if (!result.success) {
                throw new Exception("Failed to upload package. ${result.response.toString()}")
            }
            return result
        }
    })

    /*
        If the deployment has instructions for a server group, add the package to them
     */
    Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(additionalDisabledServerGroups +
                    "," +
                    (options.'enabled-server-group' ?: '') +
                    "," +
                    (options.'disabled-server-group' ?: ''))
            .each {
                /*
                    Add the deployment to the server group in a disabled state
                 */
                retryTemplate.execute(new RetryCallback<Void, Exception>() {
                    @Override
                    Void doWithRetry(RetryContext context) throws Exception {
                        println("Attempt ${context.retryCount + 1} to look up existing package ${packageName} in ${it}.")

                        def readResourceResult = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:read-resource")
                        if (!readResourceResult.success) {
                            println("Attempt ${context.retryCount + 1} to add package ${packageName} to ${it}.")

                            def result = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:add")
                            if (!result.success) {
                                throw new Exception("Failed to add ${packageName} to ${it}. ${result.response.toString()}")
                            }
                        }

                        return null
                    }
                })

            }

    /*
        Enable or disable the deployment
    */
    Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(options.'enabled-server-group' ?: '')
            .each {
        retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
            @Override
            CLI.Result doWithRetry(RetryContext context) throws Exception {
                println("Attempt ${context.retryCount + 1} to deploy package ${packageName} to ${it}.")

                def result = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:deploy")
                if (!result.success) {
                    throw new Exception("Failed to deploy package. ${result.response.toString()}")
                }
                println("Deploy package ${packageName} from ${it} succeeded")
                return result
            }
        })
    }

    /*
        Disable the deployment
    */
    Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(additionalDisabledServerGroups + "," + (options.'disabled-server-group' ?: ''))
            .each {
        retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
            @Override
            CLI.Result doWithRetry(RetryContext context) throws Exception {
                println("Attempt ${context.retryCount + 1} to undeploy package ${packageName} from ${it}.")

                def result = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:undeploy")
                if (!result.success) {
                    throw new Exception("Failed to deploy package. ${result.response.toString()}")
                }
                println("Undeploy package ${packageName} from ${it} succeeded")
                return result
            }
        })
    }
} else {
    if (options.'enabled-server-group' || options.'disabled-server-group') {
        println("The enabled-server-group and disabled-server-group options are only valid when deploying to a domain")
    }

    def disabled = (options.disabled ?: false) ? "--disabled" : ""

    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to upload package.")

            def result = jbossCli.cmd("deploy --force ${disabled} ${name} ${options.application}")
            if (!result.success) {
                throw new Exception("Failed to upload package. ${result.response.toString()}")
            }
            println("Package upload succeeded")
            return result
        }
    })
}

println("All done")

/*
    Exiting like this can have consequences if the code is run from a Java app. But
    for an Octopus Deploy script it is ok, and is actually necessary to properly exit
    the script.
 */
System.exit(0)