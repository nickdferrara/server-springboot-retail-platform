package com.retailplatform.platform.security

/** Application roles defined on the Microsoft Entra ID app registration. A token lists them in its `roles` claim. */
object Roles {
    const val MASTER_DATA_EDITOR = "MasterData.Editor"
    const val STORE_ORDERER = "Store.Orderer"
    const val BUYER = "Buyer"
    const val BUYER_APPROVER = "Buyer.Approver"
    const val ACCOUNTING_CLERK = "Accounting.Clerk"
    const val ACCOUNTING_MANAGER = "Accounting.Manager"
    const val PRICING_ANALYST = "Pricing.Analyst"
    const val INVENTORY_CONTROLLER = "Inventory.Controller"
    const val RECEIVING_CLERK = "Receiving.Clerk"
    const val STORE_MANAGER = "Store.Manager"
}
