API responses before (develop 9e7e74a81) and after (feat/wf4-ui-display-names) from a local boot: profile local, security on,
own port and database copy, same data for both. Fields added are in the "after" files.

1. load-plan-candidates: GET /fulfillment/load-plan-candidates?warehouseId=HAN as the HAN warehouse manager: customerName, deliveryProvinceName, packages[].productName
2. load-plan-detail: GET /fulfillment/load-plans/{id}: packages[].productName
3. customers-list: GET /customers?q=...: defaultShippingProvinceName (Sales staff)
4. stock-coverage: GET /orders/{id}/stock-coverage: warehouseName
5. customers-list-as-warehouse-manager (after only): 403, unchanged
