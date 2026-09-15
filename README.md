# store-app

Phase 1 skeleton: Clojure API backed by Datomic, product catalog only.
No frontend yet — this is meant to be driven from the REPL and `curl`
while you get the data layer solid, before wiring up ClojureScript.

## Setup

1. **Install the Clojure CLI** if you don't have it already
   (`brew install clojure/tools/clojure` on macOS, or see clojure.org).

2. **Install Datomic dev-local**, which lets you run a full Datomic
   database locally with no AWS account needed. Download it from your
   my.datomic.com account (free signup) and follow the dev-local install
   instructions — it's a single jar plus a `~/.datomic/dev-local.edn`
   config file pointing at a local storage directory.

3. **Check `deps.edn`** — the Datomic and reitit version numbers are
   pinned to what was current when this was written. Bump them to
   whatever's latest before you start.

4. **Stripe** — create a free Stripe account (test mode is enough for
   now) and set two env vars before running anything:

   ```bash
   export STRIPE_SECRET_KEY=sk_test_...
   export STRIPE_WEBHOOK_SECRET=whsec_...
   ```

   The secret key comes straight from your Stripe dashboard. The webhook
   secret comes from the Stripe CLI — install it, then run:

   ```bash
   stripe listen --forward-to localhost:8080/api/stripe/webhook
   ```

   which prints a `whsec_...` value to use above, and forwards real test
   events (checkout completions, etc.) to your local server while it's
   running. Keep that command running alongside your REPL while testing
   the checkout flow.

## Running it

Start a REPL with the dev alias:

```bash
clj -A:dev
```

Then open `dev/user.clj` and eval the forms in the `comment` block one
at a time: create the dev-local database, install the schema, add a
couple of test products, and start the server. Note it starts
`store.core/handler` now, not `store.api/app` directly — that's the
version with the Stripe webhook middleware wrapped around it, and you'll
need `(stripe/init! (System/getenv "STRIPE_SECRET_KEY"))` evaluated once
before creating any checkout session. From another terminal:

```bash
curl http://localhost:8080/api/products
curl http://localhost:8080/api/products/MUG-001
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"sku":"HAT-001","name":"Knit hat","price-cents":1500}'

# Restock, then place an order
curl -X POST http://localhost:8080/api/inventory/MUG-001/restock \
  -H "Content-Type: application/json" -d '{"qty":20}'

curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customer":{"email":"jane@example.com","name":"Jane Doe"},
       "items":[{"sku":"MUG-001","qty":2}]}'

curl http://localhost:8080/api/inventory/MUG-001
curl http://localhost:8080/api/low-stock?threshold=5
curl http://localhost:8080/api/customers/jane@example.com/orders

# Create a Checkout Session for that order (swap in the real order id)
curl -X POST http://localhost:8080/api/orders/12345/checkout-session \
  -H "Content-Type: application/json" \
  -d '{"success-url":"http://localhost:8080/thanks","cancel-url":"http://localhost:8080/cart"}'
```

Open the `checkout-url` from that last response in a browser and pay
with a Stripe test card (`4242 4242 4242 4242`, any future expiry, any
CVC). With `stripe listen` running, the webhook fires automatically and
the order flips to `:paid` — check it with
`curl http://localhost:8080/api/orders/12345`. You can also fire a
webhook event without going through checkout at all:
`stripe trigger checkout.session.completed`.

## What's here

- `src/store/schema.clj` — all Datomic schema, as data
- `src/store/db.clj` — connection helpers, switches between dev-local
  and Datomic Cloud via the `STORE_ENV` env var
- `src/store/products.clj` — create/update/deactivate/query functions
  for products
- `src/store/inventory.clj` — an append-only inventory ledger (restocks,
  sales, adjustments) with current-stock and low-stock queries derived
  from it, rather than a mutable counter
- `src/store/orders.clj` — places orders with line items in a single
  transaction, snapshotting prices at time of sale, then records a
  `:sale` inventory change per line
- `src/store/stripe.clj` — creates Checkout Sessions for orders and
  handles the payment-confirmation webhook, including signature
  verification
- `src/store/api.clj` — reitit routes and handlers exposing all of the
  above over HTTP/JSON
- `src/store/core.clj` — entry point for both local Jetty dev and, later,
  Ion deployment
- `dev/user.clj` — REPL scratch space to exercise all of the above,
  including a full restock → order → status update flow

## Not done yet (by design — still building up in slices)

- No auth on any write endpoint (products, orders, inventory,
  checkout-session) — needs middleware before anything here is public.
  The Stripe webhook route is the one exception: it's protected by
  signature verification instead, which is the correct mechanism for a
  server-to-server callback like that, not user auth.
- Orders don't check that stock actually covers the order before
  selling — see the note on `orders/create-order!` for why that's an
  acceptable gap for now and what to watch for
- No handling yet for Stripe's other useful events — refunds
  (`charge.refunded`), disputed charges (`charge.dispute.created`), or
  expired unpaid sessions (`checkout.session.expired`) — only payment
  confirmation is wired up so far
- No customer accounts/login — orders are tied to a customer by email
  only, no auth around "your own orders" yet
- No tests yet — worth adding once the shape of the handlers stabilizes
