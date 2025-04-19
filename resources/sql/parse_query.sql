CREATE OR REPLACE FUNCTION _pg_realtime_parse_query(query_text TEXT)
    RETURNS TABLE
            (
                object_type TEXT,
                tname       TEXT,
                cname       TEXT
            )
AS
$$
DECLARE
    rec             record;
    col_rec         record;
    is_partitioned  boolean;
    my_view         text := '_query_parser_temp_' || md5(random()::text || clock_timestamp()::text);
    query_statement text;
    full_table_name text;
BEGIN
    query_statement := 'CREATE TEMPORARY VIEW ' || my_view || ' AS ' || query_text;

    BEGIN
        EXECUTE query_statement;

        FOR rec IN
            SELECT table_schema,
                   table_name
            FROM information_schema.view_table_usage
            WHERE view_name = my_view
            LOOP
                SELECT EXISTS (SELECT 1
                               FROM pg_class c
                                        JOIN pg_namespace n ON n.oid = c.relnamespace
                               WHERE c.relkind = 'p'
                                 AND c.relname = rec.table_name
                                 AND n.nspname = rec.table_schema)
                INTO is_partitioned;

                IF rec.table_schema = 'public' THEN
                    full_table_name := rec.table_name;
                ELSE
                    full_table_name := rec.table_schema || '.' || rec.table_name;
                END IF;

                -- always return the parent table name regardless of partition status
                object_type := 'table';
                tname := full_table_name;
                cname := NULL;
                RETURN NEXT;
            END LOOP;

        -- process columns used in the query
        FOR rec IN
            SELECT DISTINCT table_schema,
                            table_name
            FROM information_schema.view_column_usage
            WHERE view_name = my_view
            LOOP
                -- Format the table name based on schema
                IF rec.table_schema = 'public' THEN
                    full_table_name := rec.table_name;
                ELSE
                    full_table_name := rec.table_schema || '.' || rec.table_name;
                END IF;

                FOR col_rec IN
                    SELECT column_name
                    FROM information_schema.view_column_usage
                    WHERE view_name = my_view
                      AND table_schema = rec.table_schema
                      AND table_name = rec.table_name
                    LOOP
                        -- Always return the parent table name for columns
                        object_type := 'column';
                        tname := full_table_name;
                        cname := col_rec.column_name;
                        RETURN NEXT;
                    END LOOP;
            END LOOP;

        EXECUTE 'DROP VIEW ' || my_view;
    END;
END;
$$ LANGUAGE plpgsql;