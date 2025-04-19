CREATE OR REPLACE FUNCTION _pg_realtime_notify_TEMPLATE_table_name()
    RETURNS TRIGGER AS
$$
DECLARE
    _tbl          oid    := (TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME)::regclass;
    text_type_oid oid    := 'text'::regtype::oid;
    max_payload   int    := 7500;
    max_col       int    := 5000;
    hashed_cols   text[] := '{}';
    typed_row     jsonb  := '{}'::jsonb;
    typed_old     jsonb  := '{}'::jsonb;
    payload       jsonb;
    cur_size      int;
    attr          record;
    new_txt       text;
    old_txt       text;
    this_oid      oid;
    candidate     record;
BEGIN

    FOR attr IN
        SELECT attname, atttypid
        FROM pg_attribute
        WHERE attrelid = _tbl
          AND attnum > 0
          AND NOT attisdropped
        ORDER BY attnum
        LOOP
            -- grab NEW (for INSERT/UPDATE)
            IF TG_OP IN ('INSERT', 'UPDATE') THEN
                EXECUTE format('SELECT ($1).%I::text', attr.attname)
                    INTO new_txt USING NEW;
            END IF;

            -- grab OLD (for UPDATE/DELETE)
            IF TG_OP IN ('UPDATE', 'DELETE') THEN
                EXECUTE format('SELECT ($1).%I::text', attr.attname)
                    INTO old_txt USING OLD;
            END IF;

            -- hash‐too‐big?
            IF TG_OP IN ('INSERT', 'UPDATE')
                AND new_txt IS NOT NULL
                AND octet_length(new_txt) > max_col
            THEN
                new_txt := encode(digest(new_txt, 'sha256'), 'hex');
                hashed_cols := array_append(hashed_cols, attr.attname);
            END IF;
            IF TG_OP IN ('UPDATE', 'DELETE')
                AND old_txt IS NOT NULL
                AND octet_length(old_txt) > max_col
            THEN
                old_txt := encode(digest(old_txt, 'sha256'), 'hex');
                hashed_cols := array_append(hashed_cols, attr.attname);
            END IF;

            -- assign OID (real or forced to text)
            this_oid := CASE
                            WHEN attr.attname = ANY (hashed_cols)
                                THEN text_type_oid
                            ELSE attr.atttypid
                END;

            -- build the “row” object
            IF TG_OP IN ('INSERT', 'UPDATE') THEN
                typed_row := typed_row ||
                             jsonb_build_object(
                                     attr.attname,
                                     jsonb_build_object('value', new_txt, 'oid', this_oid)
                             );
            ELSIF TG_OP = 'DELETE' THEN
                typed_row := typed_row ||
                             jsonb_build_object(
                                     attr.attname,
                                     jsonb_build_object('value', old_txt, 'oid', this_oid)
                             );
            END IF;

            -- build the “old_values” object (UPDATE only, and only when changed)
            IF TG_OP = 'UPDATE' AND new_txt IS DISTINCT FROM old_txt THEN
                typed_old := typed_old ||
                             jsonb_build_object(
                                     attr.attname,
                                     jsonb_build_object('value', old_txt, 'oid', this_oid)
                             );
            END IF;
        END LOOP;

    -- make initial payload & measure
    IF TG_OP = 'UPDATE' THEN
        payload := jsonb_build_object(
                'table', 'TEMPLATE_qualified_table',
                'operation', TG_OP,
                'row', typed_row,
                'old_values', typed_old,
                'hashed', to_jsonb(hashed_cols)
                   );
    ELSE
        payload := jsonb_build_object(
                'table', 'TEMPLATE_qualified_table',
                'operation', TG_OP,
                'row', typed_row,
                'hashed', to_jsonb(hashed_cols)
                   );
    END IF;
    cur_size := length(payload::text);

    -- if still too big, hash the biggest values in‐place
    FOR candidate IN
        SELECT t.col                                        AS col,
               octet_length(typed_row -> t.col ->> 'value') AS val_len
        FROM jsonb_object_keys(typed_row) AS t(col)
        WHERE NOT t.col = ANY (hashed_cols)
        ORDER BY val_len DESC
        LOOP
            EXIT WHEN cur_size <= max_payload;
            IF candidate.val_len > 64 THEN
                -- hash the row‐value
                typed_row := jsonb_set(
                        typed_row,
                        ARRAY [candidate.col, 'value'],
                        to_jsonb(
                                encode(
                                        digest(
                                                (typed_row -> candidate.col ->> 'value')::text,
                                                'sha256'
                                        ), 'hex'
                                )
                        )
                             );
                -- force to text OID
                typed_row := jsonb_set(
                        typed_row,
                        ARRAY [candidate.col, 'oid'],
                        to_jsonb(text_type_oid)
                             );

                -- and if UPDATE, hash old_values too
                IF TG_OP = 'UPDATE' AND typed_old ? candidate.col THEN
                    typed_old := jsonb_set(
                            typed_old,
                            ARRAY [candidate.col, 'value'],
                            to_jsonb(
                                    encode(
                                            digest(
                                                    (typed_old -> candidate.col ->> 'value')::text,
                                                    'sha256'
                                            ), 'hex'
                                    )
                            )
                                 );
                    typed_old := jsonb_set(
                            typed_old,
                            ARRAY [candidate.col, 'oid'],
                            to_jsonb(text_type_oid)
                                 );
                END IF;

                hashed_cols := array_append(hashed_cols, candidate.col);

                -- rebuild & re‐measure
                IF TG_OP = 'UPDATE' THEN
                    payload := jsonb_build_object(
                            'table', 'TEMPLATE_qualified_table',
                            'operation', TG_OP,
                            'row', typed_row,
                            'old_values', typed_old,
                            'hashed', to_jsonb(hashed_cols)
                               );
                ELSE
                    payload := jsonb_build_object(
                            'table', 'TEMPLATE_qualified_table',
                            'operation', TG_OP,
                            'row', typed_row,
                            'hashed', to_jsonb(hashed_cols)
                               );
                END IF;
                cur_size := length(payload::text);
            END IF;
        END LOOP;

    PERFORM pg_notify('TEMPLATE_channel_name', payload::text);
    RETURN NULL;

EXCEPTION
    WHEN OTHERS THEN
        PERFORM pg_notify(
                'TEMPLATE_channel_name',
                jsonb_build_object(
                        'table', 'TEMPLATE_qualified_table',
                        'operation', TG_OP,
                        'error', SQLERRM
                )::text
                );
        RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER _pg_realtime_trigger_TEMPLATE_table_name
    AFTER INSERT OR UPDATE OR DELETE
    ON TEMPLATE_qualified_table
    FOR EACH ROW
EXECUTE FUNCTION _pg_realtime_notify_TEMPLATE_table_name();
