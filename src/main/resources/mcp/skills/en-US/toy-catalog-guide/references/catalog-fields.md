# Catalog field reference

`list_toys` and `show_toy_catalog` return `summary`, `toys`, and `locale`;
`get_toy` returns `summary` and `toy`. The list result's `locale` identifies the
server-selected UI translation language (`en-US`, `de-DE`, or `pt-BR`), matched
from the authenticated account's locale. It is not a caller-supplied locale
override or permission to reformat the returned display strings.
Each toy contains:

| Field | Meaning |
| --- | --- |
| `toyId` | Stable UUID to pass to `get_toy` or use in the toy resource URI. |
| `name` | Authored catalog name; preserve it as returned. |
| `price` | Decimal amount in `currencyCode`, not an integer minor-unit amount. |
| `currencyCode` | ISO currency code. Compare amounts only within the same currency. |
| `priceDescription` | Price formatted for the authenticated account's locale. |
| `currencySymbol`, `currencyDescription` | Localized currency display values; a symbol alone may be ambiguous. |
| `createdAt` | ISO-8601 instant when the catalog record was created. |
| `createdAtDescription` | That instant formatted for the account's locale and time zone. |

For example, a price of `12.34` with `currencyCode` `USD` means 12.34 US dollars,
not 12.34 cents. A German account may see `12,34 $` as its display string.
Do not parse localized display strings to do arithmetic, and do not convert
between currencies without an independently supplied exchange rate.

A toy's creation timestamp is not a release date, availability promise, or
delivery estimate. A successful catalog lookup does not establish inventory
quantity or a reservation.

The ordinary `toystore://toys/<toyId>` resource contains a JSON `toy` object with
the same fields; it does not add a tool `summary`. The catalog can change between
requests, so refresh the selected toy before presenting a current comparison.
