#!/usr/bin/env bash
# Dev/load-test helper: sets a large stock for the demo SKUs directly in the inventory database.
docker exec inventory-db psql -U inventory -d inventory -c \
  "UPDATE stock SET available = 1000000, reserved = 0 WHERE sku IN ('SKU-RED-MUG', 'SKU-BLUE-MUG', 'SKU-TSHIRT-M');"
