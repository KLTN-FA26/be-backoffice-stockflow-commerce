# SCRUM-46/47 — Customer profile, account, address book and guest checkout

## Ownership decisions

- `identity.app_user` owns credentials, roles and JWT. `customer.customer` owns commercial profile
  data. `customer.user_id` is a unique logical cross-schema reference, without a foreign key.
- JWT `sub` remains the identity user id. Customer self-service resolves the profile from that id;
  the client never chooses which customer `/me` represents.
- A guest checkout creates no account and no customer profile. The order stores immutable contact,
  shipping and billing snapshots. Custom-design checkout remains account-only because confirmation
  is attributable to a customer.
- Vietnam addresses use two administrative levels: province/centrally governed city and
  ward/commune/special zone. `district` is removed. Codes and display names are both stored so a
  later rename does not make historical orders unreadable.
- Shipping and billing addresses are separate types. There is at most one default of each type;
  the first address of a type becomes default automatically.

These choices match Odoo's practical checkout model: unsigned visitors supply contact and delivery
details, signed-in customers select saved delivery addresses, and billing may be the same as or
different from delivery. The implementation keeps StockFlow's stricter module ownership and
immutable order-snapshot rules.

## Public account and checkout APIs

| API | Behaviour |
| --- | --- |
| `POST /api/v1/customers/registrations` | Creates the Identity account with `CUSTOMER`, creates its linked profile and returns the first JWT. Rate-limited by IP. |
| `POST /api/v1/identity/auth/login` | Existing login endpoint; customer email is the username. |
| `POST /api/v1/orders/guest-checkout` | Creates an account-less order, reserves stock and stores shipping/billing snapshots. It has its own idempotent `requestId` and cannot carry a design snapshot. |
| `POST /api/v1/orders` | Uses the authenticated customer's saved/default addresses and freezes contact, shipping and billing snapshots; authorised staff may place an assisted order. |

A `CUSTOMER` token can place an account order only for its own linked profile. Sales staff, order
coordinators and e-commerce admins may place an assisted order for another customer when their
resource permission also permits the action.

### Validation and retry guarantees

- Registration validates email, Vietnamese phone shape and a 10–72 character password containing
  upper-case, lower-case and digit characters. Email is trimmed and compared case-insensitively;
  both identity and customer persistence enforce uniqueness.
- Address save validates all required post-merger fields, `SHIPPING`/`BILLING` type, Vietnamese
  country code and Vietnamese phone shape. Accepted phone input is stored canonically as `+84…`.
  Membership of a ward in a province remains outside this story until the maintained official
  reference dataset exists.
- Guest checkout is anonymous but rate-limited. It accepts no design snapshot, creates no account,
  and snapshots both addresses. Retrying an identical `requestId` returns the original order;
  reusing it with any changed email, address, line, quantity or price returns
  `IDEMPOTENCY_KEY_REUSED` rather than silently accepting the earlier order.

## Profile APIs

| API | Scope |
| --- | --- |
| `GET /api/v1/customers/me` | Authenticated customer reads the profile linked to JWT `sub`. |
| `PUT /api/v1/customers/me` | Owner updates name/phone with optimistic version. |
| `GET /api/v1/customers` | Sales staff/e-commerce admin paginated search. |
| `GET /api/v1/customers/{id}` | Sales staff/e-commerce admin detail. |
| `PUT /api/v1/customers/{id}` | Sales staff/e-commerce admin assisted profile edit. |
| `POST /api/v1/customers/{id}/status` | E-commerce admin activates, deactivates or blocks a customer. |

## Address APIs

All routes are under `/api/v1/customers/{customerId}/addresses`. A customer may access only the
profile linked to their JWT; sales staff and e-commerce admin may assist another customer.

| Method/path | Behaviour |
| --- | --- |
| `GET /` | Lists shipping and billing addresses. |
| `POST /` | Adds an address; the first of its type becomes default. |
| `PUT /{addressId}` | Updates with optimistic address version. |
| `DELETE /{addressId}` | Deletes; another same-type address becomes default when necessary. |
| `POST /{addressId}/default` | Atomically changes the default for that type. |

## Important limitations outside these tickets

- The pre-existing order contract accepts an agreed unit price. A public production storefront
  must validate that price against Catalog/Pricing when that module is implemented; SCRUM-46 does
  not invent a second price owner.
- Official province/ward code membership requires a maintained administrative reference dataset.
  This story requires non-blank codes and names but deliberately does not hard-code a list that
  becomes stale after administrative changes.
- Frontend storefront/profile/address screens remain SCRUM-374/375.
