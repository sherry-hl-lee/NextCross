"""Atomic dispatch claims. Store only request/content hashes, states and timestamps."""
import os
import sqlite3
import time
from contextlib import contextmanager
from fastapi import HTTPException

DDL='''CREATE TABLE IF NOT EXISTS report_email_dispatch (
 request_hash TEXT PRIMARY KEY, digest TEXT NOT NULL, status TEXT NOT NULL,
 reason TEXT, created_epoch BIGINT NOT NULL)'''

class Store:
    def __init__(self):
        self.url=os.getenv('REPORT_EMAIL_DATABASE_URL') or os.getenv('RUNTIME_DATABASE_URL') or os.getenv('DATABASE_URL')
        self.path=os.getenv('REPORT_EMAIL_DATABASE_PATH','report_email_dispatch.db')
        if os.getenv('RENDER') and not self.url:raise RuntimeError('Cloud dispatch needs persistent database')

    @contextmanager
    def db(self):
        if self.url:
            import psycopg
            from psycopg.rows import dict_row
            with psycopg.connect(self.url,row_factory=dict_row,connect_timeout=5) as c:
                c.execute("SET LOCAL statement_timeout='5000ms'")
                yield c
        else:
            with sqlite3.connect(self.path,timeout=5) as c:
                c.row_factory=sqlite3.Row
                c.execute(DDL);yield c

    def claim(self,key,digest):
        now=int(time.time());table='iteration2_v2.report_email_dispatch' if self.url else 'report_email_dispatch'
        bind='%s' if self.url else '?'
        with self.db() as c:
            if self.url:c.execute('SELECT pg_advisory_xact_lock(76203451)')
            else:c.execute('BEGIN IMMEDIATE')
            row=c.execute(f'SELECT digest,status,reason FROM {table} WHERE request_hash={bind}',(key,)).fetchone()
            if row:return dict(row)
            # Conservative global budget counts failed/uncertain claims too.
            count=c.execute(f'SELECT COUNT(*) AS n FROM {table} WHERE created_epoch>={bind}',(now-86400,)).fetchone()['n']
            if count>=20:raise HTTPException(429,'Prototype report email daily limit reached')
            marks=','.join([bind]*5)
            c.execute(f'INSERT INTO {table}(request_hash,digest,status,reason,created_epoch) VALUES ({marks})',
                      (key,digest,'PENDING',None,now))
        return None

    def finish(self,key,status,reason):
        table='iteration2_v2.report_email_dispatch' if self.url else 'report_email_dispatch';b='%s' if self.url else '?'
        with self.db() as c:c.execute(f'UPDATE {table} SET status={b},reason={b} WHERE request_hash={b}',(status,reason,key))
