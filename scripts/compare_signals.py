#!/usr/bin/env python3
"""
Signal Comparison Script - OLD vs NEW Curated Signals
Analyzes playback logs to compare signal generation approaches
"""

import re
import sys
from datetime import datetime
from collections import defaultdict, Counter
from pathlib import Path

class SignalAnalyzer:
    """Analyze trading signals from log files"""

    def __init__(self, log_file):
        self.log_file = log_file
        self.old_signals = []
        self.new_signals = []
        self.breakouts_detected = []
        self.gate_failures = []
        self.retests_detected = []

    def parse_logs(self):
        """Parse all relevant patterns from logs"""
        print(f"📖 Parsing log file: {self.log_file}")

        with open(self.log_file, 'r', encoding='utf-8') as f:
            for line in f:
                self._parse_old_signal(line)
                self._parse_new_signal(line)
                self._parse_breakout(line)
                self._parse_gate_failure(line)
                self._parse_retest(line)

        print(f"✅ Parsed {len(self.old_signals)} OLD signals")
        print(f"✅ Parsed {len(self.new_signals)} NEW signals")
        print(f"✅ Parsed {len(self.breakouts_detected)} breakouts")
        print(f"✅ Parsed {len(self.gate_failures)} gate failures")
        print(f"✅ Parsed {len(self.retests_detected)} retests")

    def _parse_old_signal(self, line):
        """Parse OLD system signal"""
        pattern = r'🎯 TRADING SIGNAL \| scrip=(\w+) signal=(\w+) confidence=([\d.]+) \| (.+)'
        match = re.search(pattern, line)
        if match:
            self.old_signals.append({
                'timestamp': self._extract_timestamp(line),
                'scrip': match.group(1),
                'signal': match.group(2),
                'confidence': float(match.group(3)),
                'rationale': match.group(4)
            })

    def _parse_new_signal(self, line):
        """Parse NEW curated signal"""
        pattern = r'📤 ENHANCED CURATED SIGNAL EMITTED: (\w+) \| Score=([\d.]+) \| Entry=([\d.]+) \| Stop=([\d.]+) \| Target=([\d.]+) \| R:R=([\d.]+)'
        match = re.search(pattern, line)
        if match:
            self.new_signals.append({
                'timestamp': self._extract_timestamp(line),
                'scrip': match.group(1),
                'score': float(match.group(2)),
                'entry': float(match.group(3)),
                'stop': float(match.group(4)),
                'target': float(match.group(5)),
                'rr_ratio': float(match.group(6))
            })

    def _parse_breakout(self, line):
        """Parse breakout detection"""
        pattern = r'🔍 BREAKOUT DETECTED \| scrip=(\w+) \| TF_confirmations=(\d)/3 \| confluence=([\d.]+) \| volZ=([\d.]+) \| kyle=([\d.]+)'
        match = re.search(pattern, line)
        if match:
            self.breakouts_detected.append({
                'timestamp': self._extract_timestamp(line),
                'scrip': match.group(1),
                'tf_confirmations': int(match.group(2)),
                'confluence': float(match.group(3)),
                'vol_z': float(match.group(4)),
                'kyle': float(match.group(5))
            })

    def _parse_gate_failure(self, line):
        """Parse gate failures"""
        pattern = r'🚫 GATE_(\d)_FAILED \| scrip=(\w+) \| gate=(\w+) \| reason=(\S+)'
        match = re.search(pattern, line)
        if match:
            self.gate_failures.append({
                'timestamp': self._extract_timestamp(line),
                'gate_number': int(match.group(1)),
                'scrip': match.group(2),
                'gate_name': match.group(3),
                'reason': match.group(4)
            })

    def _parse_retest(self, line):
        """Parse retest detection"""
        pattern = r'✅ RETEST ENTRY CONFIRMED: (\w+) @ ([\d.]+) \| Stop=([\d.]+) \| Target=([\d.]+) \| R:R=([\d.]+)'
        match = re.search(pattern, line)
        if match:
            self.retests_detected.append({
                'timestamp': self._extract_timestamp(line),
                'scrip': match.group(1),
                'entry': float(match.group(2)),
                'stop': float(match.group(3)),
                'target': float(match.group(4)),
                'rr_ratio': float(match.group(5))
            })

    def _extract_timestamp(self, line):
        """Extract timestamp from log line"""
        match = re.match(r'(\d{2}:\d{2}:\d{2}\.\d{3})', line)
        return match.group(1) if match else None

    def analyze_old_system(self):
        """Analyze OLD system performance"""
        print("\n" + "="*60)
        print("OLD SYSTEM (TradingSignalProcessor) ANALYSIS")
        print("="*60)

        if not self.old_signals:
            print("⚠️  No OLD signals found in logs!")
            return

        total = len(self.old_signals)
        print(f"\n📊 Total Signals: {total}")

        # Signal type distribution
        signal_types = Counter(s['signal'] for s in self.old_signals)
        print("\n🎯 Signal Distribution:")
        for sig_type, count in signal_types.most_common():
            percentage = (count / total) * 100
            print(f"   {sig_type:15} {count:4} ({percentage:5.1f}%)")

        # Confidence analysis
        confidences = [s['confidence'] for s in self.old_signals]
        print(f"\n📈 Confidence Scores:")
        print(f"   Average:   {sum(confidences)/len(confidences):.3f}")
        print(f"   Min:       {min(confidences):.3f}")
        print(f"   Max:       {max(confidences):.3f}")
        print(f"   High (>0.8): {sum(1 for c in confidences if c > 0.8)} ({sum(1 for c in confidences if c > 0.8)/len(confidences)*100:.1f}%)")

        # Scrip distribution
        scrips = Counter(s['scrip'] for s in self.old_signals)
        print(f"\n📦 Unique Scrips: {len(scrips)}")
        print(f"   Top 5 scrips:")
        for scrip, count in scrips.most_common(5):
            print(f"      {scrip:10} {count:4} signals")

    def analyze_new_system(self):
        """Analyze NEW curated system performance"""
        print("\n" + "="*60)
        print("NEW CURATED SYSTEM (CuratedSignalProcessor) ANALYSIS")
        print("="*60)

        # Funnel analysis
        print(f"\n🔍 Signal Funnel:")
        print(f"   Breakouts Detected:    {len(self.breakouts_detected)}")
        print(f"   Gate Failures:         {len(self.gate_failures)}")
        print(f"   Breakouts Passed:      {len(self.breakouts_detected) - len(set(g['scrip'] for g in self.gate_failures))}")
        print(f"   Retests Detected:      {len(self.retests_detected)}")
        print(f"   Final Signals Emitted: {len(self.new_signals)}")

        if self.breakouts_detected:
            conversion_rate = (len(self.new_signals) / len(self.breakouts_detected)) * 100
            print(f"   Conversion Rate:       {conversion_rate:.1f}%")

        # Gate failure analysis
        if self.gate_failures:
            print(f"\n🚫 Gate Failure Breakdown:")
            gate_reasons = Counter(f"{g['gate_name']}: {g['reason']}" for g in self.gate_failures)
            for reason, count in gate_reasons.most_common(10):
                print(f"   {reason:40} {count:4}")

        # Signal quality analysis
        if not self.new_signals:
            print("\n⚠️  No NEW curated signals found in logs!")
            return

        total = len(self.new_signals)
        print(f"\n📊 Total Curated Signals: {total}")

        # Score analysis
        scores = [s['score'] for s in self.new_signals]
        print(f"\n📈 Curated Scores:")
        print(f"   Average:         {sum(scores)/len(scores):.1f}")
        print(f"   Min:             {min(scores):.1f}")
        print(f"   Max:             {max(scores):.1f}")
        print(f"   High (>=80):     {sum(1 for s in scores if s >= 80)} ({sum(1 for s in scores if s >= 80)/len(scores)*100:.1f}%)")
        print(f"   Medium (60-79):  {sum(1 for s in scores if 60 <= s < 80)} ({sum(1 for s in scores if 60 <= s < 80)/len(scores)*100:.1f}%)")

        # R:R analysis
        rr_ratios = [s['rr_ratio'] for s in self.new_signals]
        print(f"\n💰 Risk:Reward Ratios:")
        print(f"   Average:   {sum(rr_ratios)/len(rr_ratios):.2f}")
        print(f"   Min:       {min(rr_ratios):.2f}")
        print(f"   Max:       {max(rr_ratios):.2f}")
        print(f"   Good (>=2.0): {sum(1 for r in rr_ratios if r >= 2.0)} ({sum(1 for r in rr_ratios if r >= 2.0)/len(rr_ratios)*100:.1f}%)")

        # Scrip distribution
        scrips = Counter(s['scrip'] for s in self.new_signals)
        print(f"\n📦 Unique Scrips: {len(scrips)}")
        print(f"   Top 5 scrips:")
        for scrip, count in scrips.most_common(5):
            print(f"      {scrip:10} {count:4} signals")

    def compare_systems(self):
        """Compare OLD vs NEW systems"""
        print("\n" + "="*60)
        print("COMPARISON: OLD vs NEW")
        print("="*60)

        if not self.old_signals or not self.new_signals:
            print("⚠️  Cannot compare - missing signals from one or both systems")
            return

        # Volume comparison
        old_count = len(self.old_signals)
        new_count = len(self.new_signals)
        reduction = ((old_count - new_count) / old_count) * 100

        print(f"\n📊 Signal Volume:")
        print(f"   OLD:       {old_count} signals")
        print(f"   NEW:       {new_count} signals")
        print(f"   Reduction: {reduction:.1f}%")

        # Scrip overlap
        old_scrips = set(s['scrip'] for s in self.old_signals)
        new_scrips = set(s['scrip'] for s in self.new_signals)
        overlap = old_scrips & new_scrips

        print(f"\n📦 Scrip Coverage:")
        print(f"   OLD unique:          {len(old_scrips)}")
        print(f"   NEW unique:          {len(new_scrips)}")
        print(f"   Overlap:             {len(overlap)}")
        print(f"   Only in OLD:         {len(old_scrips - new_scrips)}")
        print(f"   Only in NEW:         {len(new_scrips - old_scrips)}")

        if overlap:
            print(f"\n   Common scrips: {', '.join(sorted(overlap))}")

        # Quality comparison
        print(f"\n⭐ Quality Metrics:")
        old_avg_conf = sum(s['confidence'] for s in self.old_signals) / len(self.old_signals)
        new_avg_score = sum(s['score'] for s in self.new_signals) / len(self.new_signals)
        print(f"   OLD avg confidence:  {old_avg_conf:.3f}")
        print(f"   NEW avg score:       {new_avg_score:.1f}")

        # Success criteria evaluation
        print(f"\n✅ Success Criteria Evaluation:")
        criteria_met = []

        # 1. Average score > 70
        if new_avg_score >= 70:
            criteria_met.append("✅ Average score >= 70")
        else:
            criteria_met.append(f"❌ Average score < 70 (actual: {new_avg_score:.1f})")

        # 2. >50% high quality
        high_quality = sum(1 for s in self.new_signals if s['score'] >= 80)
        high_quality_pct = (high_quality / len(self.new_signals)) * 100
        if high_quality_pct >= 50:
            criteria_met.append(f"✅ >50% high quality signals ({high_quality_pct:.1f}%)")
        else:
            criteria_met.append(f"❌ <50% high quality signals ({high_quality_pct:.1f}%)")

        # 3. Average R:R >= 2.0
        avg_rr = sum(s['rr_ratio'] for s in self.new_signals) / len(self.new_signals)
        if avg_rr >= 2.0:
            criteria_met.append(f"✅ Average R:R >= 2.0 ({avg_rr:.2f})")
        else:
            criteria_met.append(f"❌ Average R:R < 2.0 ({avg_rr:.2f})")

        # 4. Volume reduction 90-98%
        if 90 <= reduction <= 98:
            criteria_met.append(f"✅ Volume reduction 90-98% ({reduction:.1f}%)")
        else:
            criteria_met.append(f"⚠️  Volume reduction outside 90-98% ({reduction:.1f}%)")

        for criterion in criteria_met:
            print(f"   {criterion}")

    def generate_report(self):
        """Generate full analysis report"""
        self.parse_logs()
        self.analyze_old_system()
        self.analyze_new_system()
        self.compare_systems()

        print("\n" + "="*60)
        print("ANALYSIS COMPLETE")
        print("="*60)


def main():
    """Main entry point"""
    if len(sys.argv) < 2:
        log_file = "logs/playback-comparison.log"
        print(f"ℹ️  No log file specified, using default: {log_file}")
    else:
        log_file = sys.argv[1]

    if not Path(log_file).exists():
        print(f"❌ Error: Log file not found: {log_file}")
        print(f"\nUsage: python3 compare_signals.py [log_file]")
        sys.exit(1)

    analyzer = SignalAnalyzer(log_file)
    analyzer.generate_report()


if __name__ == "__main__":
    main()
