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

def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    c longOpt: 'controller', args: 1, argName: 'controller', 'WildFly controller'
    d longOpt: 'port', args: 1, argName: 'port', type: Number.class, 'Wildfly management port'
    u longOpt: 'user', args: 1, argName: 'username', 'WildFly management username'
    p longOpt: 'password', args: 1, argName: 'password', 'WildFly management password'
    a longOpt: 'application', args: 1, argName: 'path to artifact', 'Application to be deployed'
    n longOpt: 'name', args: 1, argName: 'application name', 'Application name'
    e longOpt: 'disabled', 'Disable the application'
    s longOpt: 'enabledServerGroup', args: 1, argName: 'server group', 'Server group to enable in'
    x longOpt: 'disabledServerGroup', args: 1, argName: 'server group', 'Server group to disable in'
}

def options = cli.parse(args)
if (!options) {
    return
}

// Show usage text when -h or --help option is used.
if (options.h) {
    cli.usage()
    return
}

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
    Connect to the server
 */
retryTemplate.execute(new RetryCallback<Void, Exception>() {
    @Override
    Void doWithRetry(RetryContext context) throws Exception {

        println("Attempting to connect. Retry count: ${context.retryCount}")

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
    def serverGroups = []

    def serverGroupResult = jbossCli.cmd("/server-group=*:read-resource")
    serverGroupResult.response.get("result").asList().forEach {
        it.get("address").asList().forEach {
            serverGroups.add(it.get("server-group").asString())
        }
    }

    def suppliedServerGroups = ImmutableList.copyOf(Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(options.enabledServerGroup ?: "" + "," + options.disabledServerGroup ?: ""))

    def missing = CollectionUtils.subtract(serverGroups, suppliedServerGroups)
    missing.forEach {
        def readResourceResult = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:read-resource")
        if (readResourceResult.success) {
            if (!readResourceResult.response.get("result").get("enabled").asBoolean()) {
                options.disabledServerGroup = options.disabledServerGroup ?
                        options.disabledServerGroup + "," + it :
                        it
            }
        }
    }

    /*
        Upload the new content
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to upload package.")

            def result = jbossCli.cmd("deploy --force ${name} ${options.application}")
            if (!result.success) {
                throw new Exception("Failed to upload package")
            }
            return result
        }
    })

    /*
        If the deployment has instructions for a server group, complete them
     */
    if (options.enabledServerGroup || options.disabledServerGroup) {

        Splitter.on(',')
                .trimResults()
                .omitEmptyStrings()
                .split(options.enabledServerGroup ?: "" + "," + options.disabledServerGroup ?: "")
                .each {
                    /*
                        Add the deployment to the server group in a disabled state
                     */
                    retryTemplate.execute(new RetryCallback<Void, Exception>() {
                        @Override
                        Void doWithRetry(RetryContext context) throws Exception {
                            println("Attempt ${context.retryCount + 1} to look up existing package.")

                            def readResourceResult = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:read-resource")
                            if (!readResourceResult.success) {
                                println("Attempt ${context.retryCount + 1} to add package.")

                                def result = jbossCli.cmd("/server-group=${it}/deployment=${packageName}:add")
                                if (!result.success) {
                                    throw new Exception("Failed to add package")
                                }
                            }

                            return null
                        }
                    })

                }

        Splitter.on(',')
                .trimResults()
                .omitEmptyStrings()
                .split(options.enabledServerGroup)
                .each {
            /*
                Enable or disable the deployment
            */
            retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
                @Override
                CLI.Result doWithRetry(RetryContext context) throws Exception {
                    println("Attempt ${context.retryCount + 1} to deploy package.")

                    def result = jbossCli.cmd("/server-group=${it}/deployment=deploy")
                    if (!result.success) {
                        throw new Exception("Failed to deploy package")
                    }
                    return result
                }
            })
        }

        Splitter.on(',')
                .trimResults()
                .omitEmptyStrings()
                .split(options.disabledServerGroup)
                .each {
            /*
                Enable or disable the deployment
            */
            retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
                @Override
                CLI.Result doWithRetry(RetryContext context) throws Exception {
                    println("Attempt ${context.retryCount + 1} to undeploy package.")

                    def result = jbossCli.cmd("/server-group=${it}/deployment=undeploy")
                    if (!result.success) {
                        throw new Exception("Failed to deploy package")
                    }
                    return result
                }
            })
        }
    }


} else {
    if (options.enabledServerGroup || options.disabledServerGroup) {
        println("The enabledServerGroup and disabledServerGroup options are only valid when deploying to a domain")
    }

    def disabled = (options.disabled ?: false) ? "--disabled" : ""

    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempting to upload package. Retry count: ${context.retryCount}")

            def result = jbossCli.cmd("deploy ${disabled} ${name} ${options.application}")
            if (!result.success) {
                throw new Exception("Failed to upload package")
            }
            return result
        }
    })
}