import groovy.xml.XmlUtil
import groovy.xml.dom.DOMCategory
import groovy.xml.DOMBuilder
import groovy.xml.QName
import java.nio.file.Files

/*
    Define and parse the command line arguments
 */
def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    t longOpt: 'tomcat-dir', args: 1, argName: 'tomcat directory', required: true, 'The Tomcat installation directory.'
    l longOpt: 'http-port', args: 1, argName: 'port', type: Number.class, 'The HTTP port to redirect to the HTTP port.'
    p longOpt: 'https-port', args: 1, argName: 'port', required: true, type: Number.class, 'The port to expose over HTTPS.'
    s longOpt: 'service', args: 1, argName: 'service', 'The Tomcat service to add the HTTPS connector to. Defaults to "Catalina".'
    k longOpt: 'keystore-file', args: 1, argName: 'path to keystore', required: true, 'Java keystore file.'
    q longOpt: 'keystore-password', args: 1, argName: 'application name', required: true, 'Keystore password.'
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

def document = DOMBuilder.parse(new StringReader(serverXml.text))
def root = document.documentElement
use(DOMCategory) {
    def updatePerformed = false

    /*
        Find the service with the supplied name
     */
    root.Service.findAll {
        (options.service?:'Catalina').equals(it['@name'])
    }
    /*
        Now add the connector
     */
    .forEach {

        def connectors = it.Connector.findAll {
            options.'https-port'.equals(it['@port'])
        }

        if (connectors.empty) {
            updatePerformed = true

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
            def updatesRequired = connectors.findAll {
                !"org.apache.coyote.http11.Http11NioProtocol".equals(it['@protocol']) ||
                    !"https".equals(it['@scheme']) ||
                    !"true".equals(it['@secure']) ||
                    !"true".equals(it['@SSLEnabled']) ||
                    !options.'keystore-file'.equals(it['@keystoreFile']) ||
                    !options.'keystore-password'.equals(it['@keystorePass']) ||
                    !"TLS".equals(it['@sslProtocol'])
            }

            updatesRequired.forEach {
                updatePerformed = true

                it['@protocol'] = "org.apache.coyote.http11.Http11NioProtocol"
                it['@scheme'] = "https"
                it['@secure'] = "true"
                it['@SSLEnabled'] = "true"
                it['@keystoreFile'] = options.'keystore-file'
                it['@keystorePass'] = options.'keystore-password'
                it['@sslProtocol'] = "TLS"
            }
        }

        /*
            Ensure the HTTP port is redirected to the HTTPS port
         */
        if (options.'http-port') {
            it.Connector.findAll {
                options.'http-port'.equals(it['@port'])
            }.forEach {
                if (!options.'https-port'.equals(it['@redirectPort'])) {
                    updatePerformed = true
                    it['@redirectPort'] = options.'https-port'
                }
            }
        }
    }

    if (updatePerformed) {
        def result = XmlUtil.serialize(root)

        serverXml.withWriter { w ->
            w.write(result)
        }

        println "HTTPS Connector added. Please restart Tomcat."
    } else {
        println "No updates made"
    }
}

System.exit(0)