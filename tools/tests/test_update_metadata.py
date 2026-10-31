import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location("metadata",Path(__file__).parents[1]/"update_metadata.py")
metadata=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(metadata)


def row(identifier="10865",name="MÜNCHEN STADT",lat="48.10",lon="11.33",elevation="515",ending=""):
    return f"{identifier:<5}      {name:<20} {lat:>6} {lon:>7} {elevation}"+ending


class MetadataTest(unittest.TestCase):
    def test_decimal_degrees_minutes_preserves_sign_and_rejects_bad_minutes(self):
        self.assertAlmostEqual(-8-40/60,metadata.decimal("-8.40"))
        self.assertAlmostEqual(-0.5,metadata.decimal("-0.30"))
        self.assertEqual(90,metadata.decimal("90.00"))
        for bad in ("12.60","12.99","nan","inf","","12.345"):
            with self.assertRaises(ValueError,msg=bad):
                metadata.decimal(bad)

    def test_catalogue_fixed_width_latin1_multiword_and_crlf(self):
        text=("HEADER\r\n-----\r\n"+row(ending="\r\n")+row("P0489","HAMBURG INNENSTADT","53.33","9.59", "", "\r\n"))
        stations=metadata.parse_catalogue(text,minimum_rows=0)
        self.assertEqual("10865\tMÜNCHEN STADT\t48.166667\t11.550000\t515",stations[0])
        self.assertEqual("P0489",stations[1].split("\t")[0])
        self.assertEqual("",stations[1].split("\t")[-1])

    def test_invalid_rows_duplicates_and_nonblank_bad_elevation_have_line_numbers(self):
        for rows in ([row(lat="91.00")],[row(lon="180.01")],[row(elevation="bad")],[row(identifier="!")],[row(),row()]):
            with self.assertRaisesRegex(ValueError,"line [34]"):
                metadata.parse_catalogue("HEADER\n-----\n"+"\n".join(rows),minimum_rows=0)

    def test_source_or_validation_failure_leaves_all_existing_outputs_intact(self):
        with tempfile.TemporaryDirectory() as temporary:
            assets=Path(temporary)
            originals={name:"old "+name for name in ("stations.tsv","timezones.tree","timezones.txt")}
            for name,value in originals.items(): (assets/name).write_text(value)
            with patch.object(metadata,"ASSETS",assets),patch.object(metadata,"get",side_effect=["bad catalogue",OSError("network failed")]):
                with self.assertRaises((ValueError,OSError,AssertionError)): metadata.main()
            self.assertEqual(originals,{name:(assets/name).read_text() for name in originals})


    def test_invalid_timezone_after_valid_catalogue_leaves_all_outputs_unchanged(self):
        catalogue="HEADER\n-----\n"+"\n".join(
            [row("10865","MUNICH","48.10","11.32"),row("10389","BERLIN","52.31","13.25"),row("P0489","HAMBURG","53.33","9.59")]
            +[row(f"{i:05}","REMOTE","80.00","80.00") for i in range(2001)]
        )
        with tempfile.TemporaryDirectory() as temporary:
            assets=Path(temporary)
            originals={name:"original "+name for name in ("stations.tsv","timezones.tree","timezones.txt")}
            for name,value in originals.items(): (assets/name).write_text(value)
            with patch.object(metadata,"ASSETS",assets),patch.object(metadata,"get",side_effect=[catalogue,'var X="\\q",T=["Europe/Berlin"];']):
                with self.assertRaises(ValueError): metadata.main()
            self.assertEqual(originals,{name:(assets/name).read_text() for name in originals})


if __name__=="__main__": unittest.main()
