# JBoss Deployment

This repo contains scripts that can be used to deploy to a WildFly / EAP server
via the cli.

These scripts have been designed to allow Octopus Deploy to deploy a release, and 
make the following assumptions:

1.  Each deployment will always overwrite an existing one.
2.  Server groups that are not specified in the command will use the new deployment (because of 1, we are always 
    updating deployments). The enabled or disabled state for unspecified server groups remains unchanged.
    
## Groovy Install

Use [SDKMAN](http://sdkman.io/) to install Groovy. It will often do a better job than the packages that come
with your Linux distribution.

## Tests

These are the scenarios that the script has been tested against.

### Standalone
* Deploy to standalone
* Deploy to standalone disabled
* Redeploy to standalone while currently enabled
* Redeploy to standalone disabled while currently enabled
* Redeploy to standalone while currently disabled
* Redeploy to standalone disabled while currently disabled

### Domain Controller
* Deploy to domain and server group while deployment does not exist
* Redeploy to domain and server group while currently exists and unassigned
* Redeploy to domain and server group while currently exists and assigned and enabled
* Redeploy to domain and server group while currently exists and assigned and disabled

* Deploy to domain and server group disabled while deployment does not exist
* Redeploy to domain and server group disabled while currently exists and unassigned
* Redeploy to domain and server group disabled while currently exists and assigned and enabled
* Redeploy to domain and server group disabled while currently exists and assigned and disabled

* Deploy to domain while deployment does not exist
* Redeploy to domain while currently exists and unassigned
* Redeploy to domain while currently exists and assigned and disabled
* Redeploy to domain while currently exists and assigned and enabled
