import groovy.xml.XmlUtil
import groovy.xml.QName
import java.nio.file.Files

/*
    Define and parse the command line arguments
 */
def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    t longOpt: 'tomcat-dir', args: 1, argName: 'tomcat directory', required: true, 'The Tomcat installation directory'
    p longOpt: 'https-port', args: 1, argName: 'port', required: true, type: Number.class, 'The port to expose over HTTPS'
    s longOpt: 'service', args: 1, argName: 'service', required: true, 'The Tomcat service to add the HTTPS connector to'
    k longOpt: 'keystore-file', args: 1, argName: 'path to keystore', required: true, 'Java keystore file'
    q longOpt: 'keystore-password', args: 1, argName: 'application name', required: true, 'Keystore password'
}

def options = cli.parse(args)
if (!options) {
    return
}

if (options.h) {
    cli.usage()
    return
}

def configPath = options.'tomcat-dir' + File.separator + "conf/server.xml"
def serverXml = new File(configPath)

if (!serverXml.exists()) {
    println "${serverXml.getCanonicalPath()} does not exist"
    System.exit(-1)
}

/*
    Do a backup
 */
Files.copy(serverXml.toPath(), new File(configPath + ".${new Date().format("yyyyMMddHHmmss")}").toPath())

def parser = new XMLParser()
parser.keepIgnorableWhitespace = true

def xml = parser.parse(serverXml)

/*
    Find the service with the supplied name
 */
xml.service.findAll {
    options.service.equals(it.@name)
}
/*
    Now add the connector
 */
.forEach {
    def connectors = it.connector.findAll {
        options.'https-port'.equals(it.@port)
    }

    if (connectors.empty) {
        it.appendNode(
                new QName("Connector"),
                [
                    port: options.'https-port',
                    protocol: "org.apache.coyote.http11.Http11NioProtocol",
                    scheme: "https",
                    secure: "true",
                    SSLEnabled: "true",
                    keystoreFile: options.'keystore-file',
                    keystorePass: options.'keystore-password',
                    sslProtocol: "TLS"
                ]
        )
    } else {
        connectors.forEach {
            it.@protocol = "org.apache.coyote.http11.Http11NioProtocol"
            it.@scheme = "https"
            it.@secure = "true"
            it.@SSLEnabled = "true"
            it.@keystoreFile = options.'keystore-file'
            it.@keystorePass = options.'keystore-password'
            it.@sslProtocol = "TLS"
        }
    }
}

serverXml.write(XmlUtil.serialize(xml))

System.exit(0)