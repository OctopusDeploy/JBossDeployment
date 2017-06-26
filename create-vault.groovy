
@Grab(group='org.wildfly', module='wildfly-security', version='10.1.0.Final')
import org.jboss.security.vault.SecurityVaultFactory
import org.jboss.as.security.vault.VaultSession

@Grab(group='org.apache.commons', module='commons-csv', version='1.4')
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser

/*
Define and parse the command line arguments
*/
def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    k longOpt: 'keystore-file', args: 1, argName: 'keystore-file', required: true, 'Java keystore file'
    q longOpt: 'keystore-password', args: 1, argName: 'keystore-password', required: true, 'Keystore password'
    e longOpt: 'enc-dir', args: 1, argName: 'enc-dir', required: true, 'Directory containing encrypted files'
    i longOpt: 'iteration', args: 1, argName: 'iteration', type: Number.class, 'Iteration count'
    s longOpt: 'salt', args: 1, argName: 'salt', '8 character salt'
    b longOpt: 'vault-block', args: 1, argName: 'vault-block', 'Vault block'
    c longOpt: 'csv', args: 1, argName: 'csv', 'CSV string containing variable names and values'
    v longOpt: 'alias', args: 1, argName: 'alias', 'Vault keystore alias'
}

def options = cli.parse(args)
if (!options) {
    return
}

if (options.h) {
    cli.usage()
    return
}

def csvText = new File(options.csv).text

def csvFormat = CSVFormat.EXCEL.newFormat(',' as char)
        .withCommentMarker('#' as char)
        .withQuote('"' as char)
def csvFileParser = CSVParser.parse(csvText, csvFormat)
def csvRecords = csvFileParser.getRecords()

if (csvRecords.any { it.size() != 2 }) {
    println "CSV file was incorrectly formatted. All rows must have 2 columns."
    System.exit(-1)
}

def vaultSession = new VaultSession(
        options.'keystore-file'.toString(),
        options.'keystore-password'.toString(),
        options.'enc-dir'.toString(),
        options.salt ?: "12345678",
        options.iteration ?: 50,
        true)
vaultSession.startVaultSession(options.alias ?: "vault")

csvRecords.forEach {
    def attributeName = it.get(0)
    def password = it.get(1)
    /*
        Normally we would call vaultSession.addSecuredAttribute(), but this method prints
        some log messages that we don't want. Since the vault is a singleton, we can just
        go straight to the SecurityVaultFactory and call what addSecuredAttribute() would
        have called anyway without the logging.
     */
    SecurityVaultFactory.get().store(options.'vault-block' ?: "vault", attributeName, password.toCharArray(), null)
}

println vaultSession.keystoreMaskedPassword

System.exit(0)

