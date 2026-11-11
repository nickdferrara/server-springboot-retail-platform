using 'main.bicep'

param environmentName = 'dev'

// Supplied by the release workflow.
param image = ''
param entraTenantId = ''
param entraApiAudience = ''
