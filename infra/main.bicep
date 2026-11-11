// One environment of the retail platform: a Container App that scales to zero, its identity, logs, and PostgreSQL.
// Deployed to a resource group by .github/workflows/release.yml. Nothing here is created until that deployment runs.

targetScope = 'resourceGroup'

@description('Short environment name, used in resource names.')
@allowed(['dev', 'test', 'prod'])
param environmentName string

@description('Azure region for every resource.')
param location string = resourceGroup().location

@description('Container image to run, including its digest, for example ghcr.io/owner/repo@sha256:...')
param image string

@description('Microsoft Entra ID tenant that issues access tokens for the API.')
param entraTenantId string

@description('Audience the API accepts in access tokens, for example api://<application id>.')
param entraApiAudience string

@description('Time zone in which business dates are resolved.')
param companyTimeZone string = 'UTC'

@description('Most replicas the application may scale out to. It always scales to zero when idle.')
@minValue(1)
param maxReplicas int = 1

@description('PostgreSQL compute size. Standard_B1ms is the smallest burstable size.')
param databaseSkuName string = 'Standard_B1ms'

@description('Daily cap on log ingestion in GB, so a logging mistake cannot run up a bill.')
param logDailyCapGb int = 1

var prefix = 'retail-${environmentName}'
var databaseName = 'retail'

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${prefix}-app'
  location: location
}

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${prefix}-logs'
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
    workspaceCapping: {
      dailyQuotaGb: logDailyCapGb
    }
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: '${prefix}-${uniqueString(resourceGroup().id)}'
  location: location
  sku: {
    name: databaseSkuName
    tier: 'Burstable'
  }
  properties: {
    version: '17'
    storage: {
      storageSizeGB: 32
      autoGrow: 'Disabled'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
    // No passwords: the application signs in with its managed identity.
    authConfig: {
      activeDirectoryAuth: 'Enabled'
      passwordAuth: 'Disabled'
      tenantId: subscription().tenantId
    }
  }

  resource retail 'databases' = {
    name: databaseName
  }

  // Without a virtual network, the Container App reaches the database over Azure's network.
  resource allowAzureServices 'firewallRules' = {
    name: 'AllowAllAzureServices'
    properties: {
      startIpAddress: '0.0.0.0'
      endIpAddress: '0.0.0.0'
    }
  }
}

// The application's identity administers the database so Flyway can create schemas and tables.
module databaseAdministrator 'database-administrator.bicep' = {
  name: 'database-administrator'
  params: {
    serverName: database.name
    principalId: identity.properties.principalId
    principalName: identity.name
  }
}

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${prefix}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${prefix}-app'
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identity.id}': {}
    }
  }
  // The application signs in to the database at startup, so its identity must already be the administrator.
  dependsOn: [
    databaseAdministrator
  ]
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8080
        transport: 'auto'
        allowInsecure: false
      }
    }
    template: {
      terminationGracePeriodSeconds: 30
      containers: [
        {
          name: 'app'
          image: image
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            {
              name: 'SPRING_PROFILES_ACTIVE'
              value: environmentName
            }
            {
              name: 'DATABASE_URL'
              value: 'jdbc:postgresql://${database.properties.fullyQualifiedDomainName}:5432/${databaseName}?sslmode=require'
            }
            {
              name: 'DATABASE_USERNAME'
              value: identity.name
            }
            {
              name: 'AZURE_CLIENT_ID'
              value: identity.properties.clientId
            }
            {
              name: 'ENTRA_TENANT_ID'
              value: entraTenantId
            }
            {
              name: 'ENTRA_API_AUDIENCE'
              value: entraApiAudience
            }
            {
              name: 'COMPANY_TIME_ZONE'
              value: companyTimeZone
            }
          ]
          probes: [
            {
              type: 'Startup'
              httpGet: {
                path: '/actuator/health/liveness'
                port: 8080
              }
              periodSeconds: 5
              failureThreshold: 30
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/actuator/health/liveness'
                port: 8080
              }
              periodSeconds: 10
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/actuator/health/readiness'
                port: 8080
              }
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        // Scale to zero when idle, so an unused environment uses no compute.
        minReplicas: 0
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
}

@description('Public address of the application.')
output applicationUrl string = 'https://${app.properties.configuration.ingress.fqdn}'
