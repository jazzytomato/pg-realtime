# pg-realtime

A simple Clojure library to create "live queries" for PostgreSQL - you provide a SQL query, and get back an atom that automatically
updates whenever the underlying data changes. This enables reactive applications with real-time data synchronization
without having to implement complex change detection or polling.

pg-realtime does not efficiently stream changes directly into your data. It helps you re-run your query at the right time. 

Key features:

- **No infrastructure changes** - Works with your existing PostgreSQL database
- **Real-time updates** - Subscribe to queries and get results that automatically update
- **Efficient** - Uses PostgreSQL's native notification system. Multiple clients/users *can* (but not necessarily *should*) subscribe to the same query.
- **Throttling** - Prevents update storms during bulk operations
- **Customizable refresh logic** - Control exactly when a query refresh should occur
- **Partition-aware** - Automatically handles partitioned tables

pg-realtime is framework-agnostic, but was designed to be used with [Clojure Electric](https://github.com/hyperfiddle/electric).

## Rationale

I wanted to create a *simple* way to get real-time updates from PostgreSQL queries in Clojure. 
A WAL-based change capture would be ideal but is a lot more complex to implement.
Existing alternatives are no doubt more performant but all required extra pieces of infrastructure, or are designed for direct frontend connections e.g.  [Materialize](https://materialize.com/), [Debezium](https://debezium.io/), [Supabase realtime](https://github.com/supabase/realtime), [Electric SQL](https://electric-sql.com/) 

Current & hacky approach with Clojure Electric [is to mark the database as "dirty"](https://gist.github.com/dustingetz/1960436eb4044f65ddfcfce3ee0641b7) at the application level,
You may want to start with this if you're toying around, but it doesn't scale well. This also wouldn't work with multiple servers as the "dirty" state is in memory, of if any other service updates the database.


## How It Works

pg-realtime uses a combination of PostgreSQL's notification system (LISTEN/NOTIFY) and automatic trigger management:

1. **Query Analysis**: When you subscribe to a query, the library parses the query by using a temporary view and PG
   system catalog to identify which tables and columns are being accessed.

2. **Trigger Installation**: For each table involved in the query, the library installs a trigger function (if not already present)
   and an AFTER INSERT/UPDATE/DELETE trigger that sends notifications when data changes.

3. **Notification Handling**: A background process (core.async go block) listens for notifications and re-run your query when relevant changes occur.

4. **Smart Updates**: The library uses throttling and optional custom refresh functions to handle the notification and prevent
   excessive refreshes.

### Limitations

This library offers a pragmatic approach to enable realtime queries with no infrastructure changes. It is developed with "best effort" in mind, so if you need better performance, consider using a change data capture solution.

- The query parser is basic and only identifies used tables and columns. It does not track the values used in the query. This can be improved by providing a `refresh?` option when subscribing.
- With the default refresh behaviour, it is likely that the query will be refreshed more often than necessary (however, the atom won't update if the result has not changed).
- Good refresh strategies aren't always straightforward to write for non-trivial queries, especially with joins. See the advanced usage section for more details.
- PG NOTIFY has a limit of 8kb per message. If a notification payload exceed this size, the trigger function will gradually hash column values by finding the best candidates.
  You can then use the `:hashed?` key in the notification data to identify which columns are hashed in the notification payload, and handle them accordingly. Columns exceeding 5kb will always be hashed.

### Schema Impact

When initialized, pg-realtime will create:

- A PostgreSQL function named `_pg_realtime_parse_query` to analyze queries.
- For each relevant table, a trigger function named `_pg_realtime_notify_[table_name]`
- For each relevant table, a trigger named `_pg_realtime_trigger_[table_name]`

These database objects are created in your database schema and will remain even after your application shuts down. This
is intentional, as managing the lifecycle of these objects can be complex in a multi-server environment.

If you need to clean up these objects, you can do so with `pgrt/destroy-pg-realtime-objects!` (may require additional permissions).

## Dependencies

- com.github.igrishaev/pg2-core. This library offers better support for PG LISTEN/NOTIFY than jdbc drivers. See https://github.com/igrishaev/pg2 for more details.
  You will need to use [pg2 execute](https://github.com/igrishaev/pg2/blob/master/docs/query-execute.md#execute) to run your queries. JDBC support could be added if people are interested, please submit an issue.
- org.clojure/core.async

## Requirements

- PostgreSQL v?? or later (todo: check compatibility)
- A postgres user with the ability to create triggers and functions (using the credentials in the `start!` function, queries can use a different user)
- pgcrypto extension (for hashing large column values), this is automatically installed during startup.

## Installation

Add the following dependency to your `project.clj` or `deps.edn`:

```clojure
;; lein
[com.github.jazzytomato/pg-realtime "0.1.0"]

;; deps
com.github.jazzytomato/pg-realtime {:mvn/version "0.1.0"}
```

## Basic Usage

```clojure
(require '[pg-realtime.core :as pgrt]
         '[pg.core :as pg])

(def db-config {:host     "localhost"
                :port     5432
                :user     "postgres"
                :password "postgres"
                :database "mydb"})

;; Initialize the system with your database config
(pgrt/start! db-config {})

;; Create a connection for running queries
(def conn (pg/pool db-config))

;; Subscribe to a query
(def !users (pgrt/sub ::all-users 
                          conn
                          "SELECT id, email, display_name FROM users"))

;; Add a watch to react to changes
(add-watch !users ::watcher
           (fn [_ _ _old-state new-state]
             (println "Users data changed: " new-state)))

;; Running an update will update the user atom:
(pg/execute conn "update users set display_name = 'john' where id = '0195b481-64f3-71a8-bc13-dc948403a1c4'")

;;=> {:updated 1}
;;Users data changed:  [{:id #uuid "01961b27-0d79-735d-96b8-89a48558ef0f", :email someone@example.com, :display_name someOne} {:id #uuid "0195b061-8c12-7329-873e-67ab19b6e2e3", :email john@example.com, :display_name john}]

;; Running an update on a column not used in the query will not update the atom:
(pg/execute conn "update users set avatar_url = 'http://example.com/avatar.png' where id = '0195b481-64f3-71a8-bc13-dc948403a1c4'")
;;=> {:updated 1}

;; When you're done, clean up
(pgrt/unsub ::all-users)

;; Shut down the system when your app exits. This removes all active subscriptions.
(pgrt/shutdown!)
;; shutdown! does not remove the triggers or functions. You can do this manually or call `pgrt/destroy-pg-realtime-objects!`.
```

### Filtering notifications

```clojure
(def !active-users (pgrt/sub ::active-users 
                          conn
                          "SELECT id, email, display_name FROM users WHERE status = 'active'"
                          {:refresh? {:users {:status "active"}}}))
```
This will refresh the query if:
- A new user is inserted with status "active"
- An existing user status is changed from anything to "active"
- The id, email or display_name of an existing user with status "active" is updated
- An existing user is updated from status "active" to something else
- An existing user with status "active" is deleted

If one of the used table is not provided in the `refresh?` map, the default behaviour will be used for that table (i.e. refresh on any change for used columns).

Filtering notifications can become tricky with joins & more complex queries, see the advanced usage section for more details.

### Subscribing to an existing subscription

```clojure
(def !active-users (pgrt/sub ::active-users))
```
This will return the same atom as the one created in the previous example.

### Security considerations
The query ID is important and should be properly scoped in a multi-tenant application.
```clojure
;; bad
(let [!my-orders (pgrt/sub ::my-orders
                       conn
                       "SELECT * FROM orders where user_id = $1"
                       {:params [user-id]})])

;; good
(let [!my-orders (pgrt/sub (str user-id "-orders") ;; include the user id in the query ID
                       conn
                       "SELECT * FROM orders where user_id = $1"
                       {:params [user-id]})])

;; good, to share across users
(def all-items (pgrt/sub ::all-items) conn "SELECT * FROM items")
```
In the first example, there could be a security issue where orders of user 1 are shown to user 2 if:
1) user 1 subscribe to ::my-orders
2) user 2 subscribes to ::my-orders
3) user 1 now sees user 2 orders

For resources that are shared across users, you can use a common query ID (e.g. `::all-orders`).

## Electric integration example
```clojure
(e/server
  (let [status (e/watch !status) ;; e.g. coming from a UI filter
        sub-id (str user-id "-orders")
        !orders (pgrt/sub sub-id "SELECT id, status FROM orders WHERE user_id = $1 AND status = $2" {:params [user-id status]})]
    (e/on-unmount #(pgrt/unsub sub-id))
    (dom/table
      (e/for [[{:keys [id status]}] (e/diff-by :id (e/watch !orders))]
             (dom/tr (dom/td (dom/text id)) (dom/td (dom/text status)))))))
```

If the `!status` atom changes, the expression `(pgrt/sub ...)` will be re-evaluated and the subscription will be replaced with a new one. The returned atom is the same.
It's important to unsub from the previous subscription to avoid resource leak, so we register an `on-unmount` callback to do this.


## Advanced Usage

### Custom throttle time
By default, pg-realtime will throttle notifications to 500ms. This means that if multiple notifications are received within this time frame, only the last one will trigger a refresh.
Depending on your use case, you might want more or less frequent updates. You can set a custom throttle time in milliseconds when creating the subscription.
```clojure
(def !orders (pgrt/sub ::orders
                       conn
                       "SELECT * FROM orders"
                       {:throttle-ms 2000})) ;; 2 seconds
```

### Refresh strategies

#### Default
Given the following query:
```clojure
(def !pending-orders (pgrt/sub ::pending-orders
                              conn
                              "SELECT o.id, o.code, count(*) as item_count, sum(i.price) as total_price
                               FROM orders o
                               INNER JOIN items i ON i.order_id = o.id
                               WHERE status = 'pending' GROUP BY 1, 2" ))
```
By default, this query will refresh when any row is inserted in or deleted from the `orders` & `items` table. Or if the `orders.id`, `orders.code`, `orders.status`, `items.price` columns are updated.
This is because the query parser only tracks used table and columns names. It doesn't track the values used in the query.
This may be fine for most cases, but if your tables are very large, or you have a lot of concurrent updates, you may want to limit the refreshes to pending orders.
You can do this by providing a custom refresh function. This function will receive the notification data and should return true if the subscription should be refreshed, or false otherwise.

#### Custom refresh function
When using a custom refresh function, you receive notification data with the following structure:

```clojure
{:operation :update    ; one of :insert, :update, :delete
 :table     :orders    ; the changed table (as a keyword)
 :row {:id 1
       :code "12345"
       :created_at "2023-01-01T00:00:00Z"
       :status   "shipped"
       :invoice_pdf "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"}
 :changes   {; map of changed columns with [old-value new-value]
             :status   ["pending" "shipped"]
             }
 :hashed?   #{:invoice_pdf}        ; Which column values are hashed (may occur with large payloads)
 }
```

For schema-qualified tables, the `:table` will be namespaced, like `:schema/table`.

Let's implement a custom refresh function that only refreshes pending orders:

```clojure
(def !pending-orders (pgrt/sub ::pending-orders
                              conn
                              "SELECT o.id, o.code, count(*) as item_count, sum(i.price) as total_price
                               FROM orders o
                               INNER JOIN items i ON i.order_id = o.id
                               WHERE status = 'pending' GROUP BY 1, 2"
                              {:refresh?    (fn [_ _ {:keys [row changes table _operation]}]
                                              (case table
                                                :items :tracked-columns ;; special keyword to fall back to default behaviour for this table
                                                :orders (or (some #{"pending"} (:status changes))
                                                            (= "pending" (:status row)))))}))
```
Now this is an improvement, the only changes in the order table triggering a refresh would be relating to pending orders (e.g. a pending order code is updated, a new pending order is inserted, an order status changes from pending to shipped etc.).
But it still has a flaw: items changes not related to pending order will trigger a refresh. This is because the notification from the items table doesn't include the order status. The items table only has the order id.

#### Custom refresh function using current results

Read carefully as this method, while it can offer some performance improvements, can also have some surprising behaviour.

When an "items" notification arrives, you can use the current query results to check the item belongs to an order that is already in the current results:

```clojure
(def !pending-orders 
  (pgrt/sub 
    ::pending-orders
    conn
    "SELECT o.id, o.code, count(*) as item_count, sum(i.price) as total_price
     FROM orders o
     INNER JOIN items i ON i.order_id = o.id
     WHERE status = 'pending' GROUP BY 1, 2"
    {:refresh? (fn [_ result {:keys [row changes table]}]
                 (case table
                   :items (some #(= (:id %) (:order_id row)) result)
                   :orders (or (some #{"pending"} (:status changes))
                               (= "pending" (:status row)))))}))
```

Because this is a common use case, you can provide a map instead of a function as an option, and use the special namespaced keyword `:result/column_name`. The library will automatically check if the value in the notification matches a value in the current results. For example:

```clojure
(def !pending-orders
  (pgrt/sub 
    ::pending-orders
    conn
    "SELECT o.id, o.code, count(*) as item_count, sum(i.price) as total_price
     FROM orders o
     INNER JOIN items i ON i.order_id = o.id
     WHERE status = 'pending' GROUP BY 1, 2"
    {:refresh? {:items {:order_id :result/id} ;; Using the special :result namespace, checks if the order id in the notification matches an :id in the current results
                :orders {:status "pending"}}}))
```
`:result/id` works whether your result is a collection or a single row.

So, this is nice, but unfortunately it has some edge cases which limits is usability. It won't work if the data is not present in the existing result set, and this may happen for example if:
- The data is not in the current results because of a join. For example, in the example above, if the order is not in the current results because it has no items. Consider using an outer join.
- The query is an aggregate
- The query returns a limited number of rows (e.g. `LIMIT 10`) 

#### Custom refresh function using a lookup query

Alternatively, if you don't have the data you need in the current results (and you can't add it), you can run a query to check if the order is pending or not. This requires an extra query to the database but can be faster than refreshing the main query. It depends on your use case.
Be careful, as opposed to the main query, the refresh? function isn't throttled, so it may cause performance issue on high traffic tables.
```clojure
(def !pending-orders
  (pgrt/sub ::pending-orders
            conn
            "SELECT o.code, count(*) as item_count, sum(i.price) as total_price
             FROM orders o
             INNER JOIN items i ON i.order_id = o.id
             WHERE status = 'pending' GROUP BY 1"
            {:refresh? (fn [conn _ {:keys [row changes table]}]
                         (case table
                           :items
                           (pg/execute conn
                                       "SELECT 1 FROM orders WHERE id = ? AND status = 'pending'"
                                       {:params [(:order_id row)] :first? true})

                           :orders (or (some #{"pending"} (:status changes))
                                       (= "pending" (:status row)))))}))
```

#### Error Handling
Because queries are run in a separate thread (when a change notification is received), errors are caught & logged with at the `:error` level.
If you want to handle errors differently, you can provide a custom error handler function.
```clojure
;; Custom error handler
(def !products (pgrt/sub ::products
                         conn
                         "SELECT * FROM products"
                         {:error-handler (fn [e query]
                                           (log/error "Query failed:" query e)
                                           (notify-admin! e "Product query failed"))}))
```

Some error may also occur during the trigger notification or when processing a notification, by default those errors are logged at the `:error` level. If you want to handle them differently, you can provide a custom error handler function.
```clojure
(pgrt/start! db-config
        {:error-handler (fn [e]
                          (notify-admin! e "Error when processing notification"))})
```

## Contributing
We appreciate and welcome your contributions or suggestions. Please feel free to file issues or pull requests.

## License
Distributed under the Eclipse Public License version 2.0.