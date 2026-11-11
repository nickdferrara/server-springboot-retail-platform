# Infrastructure

`main.bicep` (with its module `database-administrator.bicep`) describes one environment: a Container App that scales to zero, its managed identity, a Log Analytics
workspace with a daily cap, and an Azure Database for PostgreSQL Flexible Server. `dev.bicepparam` holds the dev
settings.

**Status: never deployed.** The templates are compiled in CI but have not been applied to a subscription, so expect
to adjust them on the first deployment.

## What costs money

| Resource | Cost when idle |
| --- | --- |
| Container App (consumption, scales to zero) | None while idle; usage draws on the monthly free grant |
| Managed identity | None |
| Log Analytics workspace | None within the free monthly allowance; capped at 1 GB a day |
| PostgreSQL Flexible Server (`Standard_B1ms`, 32 GB) | Billed for every hour it exists, whether or not it is used |

PostgreSQL is the only resource with a standing charge. Confirm current prices in the Azure pricing calculator before
deploying, and delete the resource group to stop all charges.

The image comes from the GitHub Container Registry, which is free for a public repository, instead of Azure
Container Registry.

## How deployment is switched on

The `deploy to dev` job in `.github/workflows/release.yml` is skipped until the repository variable
`AZURE_DEPLOY_ENABLED` is `true`. Before setting it:

1. Create a resource group for dev.
2. Create an app registration for GitHub Actions with a federated credential for this repository's `dev` environment,
   and give it the Contributor role on that resource group only.
3. Create the app registration for the API, with its application roles (see `Roles.kt`).
4. Set the repository variables `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`,
   `AZURE_RESOURCE_GROUP`, and `ENTRA_API_AUDIENCE`. None of these is a secret.
5. Make the container image package public, or give the Container App registry credentials.

## Not done yet

- The application does not yet obtain a database token from its managed identity, so it cannot sign in to a
  PostgreSQL server that has password authentication disabled. This must be added before the first deployment.
- No virtual network or private endpoints; the database accepts connections from Azure services.
- No Key Vault, App Configuration, Storage account, or Application Insights.
- Only dev; test and prod promotion comes later.
