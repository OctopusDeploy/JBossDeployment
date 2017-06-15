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
    a longOpt: 'key-file', args: 1, argName: 'path to artifact', required: true, 'Application to be deployed'
    n longOpt: 'certificate-file', args: 1, argName: 'application name', 'Application name'
    s longOpt: 'server-group', args: 1, argName: 'server group', 'Server group to enable in'
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

if (jbossCli.getCommandContext().isDomainMode()) {

} else {
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add security realm.")

            def existsResult = jbossCli.cmd("/core-service=management/security-realm=ssl-realm:read-resource")
            if (!existsResult.success) {
                def addResult = jbossCli.cmd("/core-service=management/security-realm=ssl-realm::add()")
                if (!addResult.success) {
                    throw new Exception("Failed to create security realm. ${addResult.response.toString()}")
                }
            }
        }
    })
}

///core-service=management/security-realm=ssl-realm/:add()
///core-service=management/security-realm=ssl-realm/server-identity=ssl/:add(certificate-key-file=C:\key.pem, certificate-file=C:\certificate.pem, keystore-password="Password01!")
///subsystem=undertow/server=default-server/https-listener=https/:add(socket-binding=https, security-realm=ssl-realm)