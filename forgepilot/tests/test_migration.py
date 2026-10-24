"""Fail closed on mixed-industry seeds, source fabrication and identity mistakes."""
from pathlib import Path
import importlib.util
import json
import re
import sys
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from build_catalog import build
from generate import statements


class IndustrialMigrationTests(unittest.TestCase):
    def test_baseline_only_contains_platform_ddl(self):
        sql=(ROOT.parent/'bemodel-server/src/main/resources/db/industrial/V1__industrial_platform.sql').read_text()
        stmts=list(statements(sql))
        self.assertEqual(len(stmts),59)
        for statement in stmts:
            self.assertRegex(statement,r'^(CREATE TABLE(?: IF NOT EXISTS)?|ALTER TABLE)\s+(bm_|link_|rca_)')
            self.assertNotRegex(statement,r'(?i)\bdemo_\w+\.')
            self.assertNotRegex(statement,r'(?i)^\s*(INSERT|UPDATE|DELETE|DROP|TRUNCATE|CREATE DATABASE)')

    def test_source_connections_and_approvals_are_not_fabricated(self):
        sql=(ROOT.parent/'bemodel-server/src/main/resources/db/industrial/V2__industrial_ontology.sql').read_text()
        stmts=list(statements(sql))
        for table in ('bm_datasource','bm_mapping','bm_physical_table','bm_release'):
            self.assertFalse(any(re.match(r'INSERT INTO '+table+r'\b',s) for s in stmts))
        concepts=[s for s in stmts if s.startswith('INSERT INTO bm_concept ')]
        self.assertEqual(len(concepts),45)
        self.assertTrue(all("'DRAFT'" in s for s in concepts))

    def test_catalog_reference_and_units_are_closed(self):
        m=build();entities={e['code']:e for e in m['entities']}
        self.assertEqual(len(entities),45)
        self.assertEqual(sum(e['source_collection'] is not None for e in entities.values()),18)
        for relation in m['relations']:
            self.assertIn(relation['from_entity'],entities)
            self.assertIn(relation['to_entity'],entities)
            self.assertFalse(relation['transitive'])
        for entity in entities.values():
            self.assertTrue(any(a['is_key'] for a in entity['attributes']))
            for attribute in entity['attributes']:
                if attribute['code'].endswith('_kg'): self.assertEqual(attribute['unit'],'kg')
        for metric in m['metrics']:
            self.assertIn(metric['entity'],entities)
            self.assertFalse(metric['execution_enabled'])
        for action in m['actions']: self.assertFalse(action['execution_enabled'])

    def test_generated_catalog_is_reproducible_and_matches_java_resource(self):
        m=build()
        self.assertEqual(m,json.loads((ROOT/'catalog.json').read_text()))
        self.assertEqual(m,json.loads((ROOT.parent/'bemodel-server/src/main/resources/industrial/catalog.json').read_text()))

    def test_sql_literals_do_not_split_at_semicolons_or_comment_characters(self):
        fixture="-- seed\nINSERT INTO x VALUES ('a;b', 'it''s -- a string', '/*text*/'); /* skip */ ALTER TABLE bm_x ADD a INT;"
        parts=list(statements(fixture))
        self.assertEqual(len(parts),2)
        self.assertIn("'a;b'",parts[0])
        self.assertTrue(parts[1].startswith('ALTER TABLE bm_x'))
        with self.assertRaises(ValueError): list(statements("INSERT INTO x VALUES ('unclosed"))

if __name__=='__main__': unittest.main()
