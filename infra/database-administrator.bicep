// Makes a managed identity the Microsoft Entra administrator of a PostgreSQL server.
// A module of its own because the administrator is named by the identity's principal ID, which is only known once
// the identity exists.

@description('Name of the PostgreSQL Flexible Server.')
param serverName string

@description('Principal (object) ID of the managed identity.')
param principalId string

@description('Name of the managed identity; it becomes the database role name.')
param principalName string

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' existing = {
  name: serverName
}

resource administrator 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2024-08-01' = {
  parent: server
  name: principalId
  properties: {
    principalType: 'ServicePrincipal'
    principalName: principalName
    tenantId: subscription().tenantId
  }
}
