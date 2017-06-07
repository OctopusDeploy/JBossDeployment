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
final DEFAULT_PORT = 9990

/*
    Define and parse the command line arguments
 */
def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    c longOpt: 'controller', args: 1, argName: 'controller', 'WildFly controller'
    d longOpt: 'port', args: 1, argName: 'port', type: Number.class, 'Wildfly management port'
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
                options.controller ?: DEFAULT_HOST,
                options.port ?: DEFAULT_PORT,
                options.user,
                options.password.toCharArray())
        return null
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
        enabledServerGroup or disabledServerGroup options, it is added to the disabledServerGroup
        option.
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

    def serverGroups = serverGroupResult.response.get("result").asList().collect {
        it.get("address").asList().collect {
            it.get("server-group").asString()
        }
    }.flatten()

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
                println "Adding ${value} to disabled-server-group options to prevent accidential enablement of the deployment"
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
        If the deployment has instructions for a server group, complete them
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

    Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(options.'enabled-server-group' ?: '')
            .each {
        /*
            Enable or disable the deployment
        */
        retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
            @Override
            CLI.Result doWithRetry(RetryContext context) throws Exception {
                println("Attempt ${context.retryCount + 1} to deploy package ${packageName} to ${it}.")

                def result = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:deploy")
                if (!result.success) {
                    throw new Exception("Failed to deploy package. ${result.response.toString()}")
                }
                return result
            }
        })
    }

    Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(additionalDisabledServerGroups + "," + (options.'disabled-server-group' ?: ''))
            .each {
        /*
            Enable or disable the deployment
        */
        retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
            @Override
            CLI.Result doWithRetry(RetryContext context) throws Exception {
                println("Attempt ${context.retryCount + 1} to undeploy package ${packageName} from ${it}.")

                def result = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:undeploy")
                if (!result.success) {
                    throw new Exception("Failed to deploy package. ${result.response.toString()}")
                }
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
            println("Attempting to upload package. Retry count: ${context.retryCount}")

            def result = jbossCli.cmd("deploy ${disabled} ${name} ${options.application}")
            if (!result.success) {
                throw new Exception("Failed to upload package. ${result.response.toString()}")
            }
            return result
        }
    })
}

return