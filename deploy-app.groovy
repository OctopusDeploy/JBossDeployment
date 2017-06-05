#!/usr/bin/env groovy

@Grab(group='org.wildfly.core', module='wildfly-embedded', version='2.2.1.Final')
@Grab(group='org.wildfly.security', module='wildfly-security-manager', version='1.1.2.Final')
@Grab(group='org.wildfly.core', module='wildfly-cli', version='2.2.1.Final')
import org.jboss.as.cli.scriptsupport.*

@Grab(group='org.springframework.retry', module='spring-retry', version='1.2.0.RELEASE')
import org.springframework.retry.*
import org.springframework.retry.support.*
import org.springframework.retry.policy.*

@Grab(group='commons-io', module='commons-io', version='2.5')
import org.apache.commons.io.*

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
    e longOpt: 'enabled', 'Enable the application'
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

cli = CLI.newInstance()

/*
   Build up a retry template
 */
def retryTemplate = new RetryTemplate()
def retryPolicy = new SimpleRetryPolicy()
retryTemplate.setRetryPolicy(retryPolicy)

/*
    Connect to the server
 */
retryTemplate.execute(new RetryCallback<Void, Exception>() {
    @Override
    Void doWithRetry(RetryContext context) throws Exception {
        cli.connect(
                options.controller ?: DEFAULT_HOST,
                options.port ?: DEFAULT_PORT,
                options.user,
                options.password.toCharArray())
        return null
    }
})

def appName = options.name ?: FilenameUtils.getName(options.application)

/*
    Find out if the app is already uploaded
 */
def appExists = retryTemplate.execute(new RetryCallback<Boolean, Exception>() {
    @Override
    Boolean doWithRetry(RetryContext context) throws Exception {
        return cli.cmd("/deployment=${appName}:read-resource()").success
    }
})

/*
    Upload the package
 */
def disabled = options.enabled?:true ? "" : "--disabled"
def name = options.name ? "--name=${options.name}" : ""

if (cli.getCommandContext().isDomainMode()) {

} else {
    def appUploadResult = retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            return cli.cmd("deploy --force ${disabled} ${name} ${options.application}")
        }
    })
}