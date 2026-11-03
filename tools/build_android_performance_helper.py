#!/usr/bin/env python3
"""Compile the isolated performance fixture's shell bridge; no app build changes."""
import argparse
import subprocess
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk',required=True,type=Path)
parser.add_argument('--java-home',required=True,type=Path)
parser.add_argument('--output',required=True,type=Path)
args=parser.parse_args()
if args.output.exists() and any(args.output.iterdir()):parser.error('Output must be new or empty')
classes=args.output/'classes';dex=args.output/'dex';classes.mkdir(parents=True);dex.mkdir()
android=args.sdk/'platforms/android-37.0/android.jar'
source=Path(__file__).resolve().parent/'android/PerformanceFixture.java'
subprocess.run([str(args.java_home/'bin/javac'),'-source','8','-target','8','-bootclasspath',str(android),'-d',str(classes),str(source)],check=True)
subprocess.run([str(args.sdk/'build-tools/36.0.0/d8'),'--min-api','26','--lib',str(android),'--output',str(dex),*map(str,classes.glob('*.class'))],check=True)
print(dex/'classes.dex')
