#!/usr/bin/env groovy

@Grab(group='org.wildfly.core', module='wildfly-embedded', version='2.2.1.Final')
@Grab(group='org.wildfly.security', module='wildfly-security-manager', version='1.1.2.Final')
@Grab(group='org.wildfly.core', module='wildfly-cli', version='2.2.1.Final')
import org.jboss.as.cli.scriptsupport.*

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
cli.connect(
        options.controller ?: DEFAULT_HOST,
        options.port ?: DEFAULT_PORT,
        options.user,
        options.password.toCharArray())

def appName = options.name ?: FilenameUtils.getName(options.application)

def appExists = cli.cmd("/deployment=${appName}:read-resource()").success

if (appExists) {
    println("Application ${appName} exists. Deploying with --force.")
} else {
    println("Application ${appName} does not exist.")
}

def force = appExists ? "--force" : ""
def disabled = options.enabled?:true ? "" : "--disabled"
def name = options.name ? "--name ${options.name}" : ""

if (cli.getCommandContext().isDomainMode()) {

} else {
    def deployAppResult = cli.cmd("deploy ${force} ${disabled} ${name} ${options.application}")
}