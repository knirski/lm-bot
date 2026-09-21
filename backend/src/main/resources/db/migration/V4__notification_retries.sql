-- Delivery retries look up the latest delivery event per slot; without this
-- index that is a scan of the monitor's whole event log.
create index idx_monitor_events_slot_delivery
    on monitor_events (monitor_id, slot_key, created_at desc);
