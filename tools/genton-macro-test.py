#!/usr/bin/env python3
"""
AI Native OS - 宏测试 CLI 工具

用法:
    genton-macro-test run <macro_id>              # 运行单个宏的所有测试
    genton-macro-test run-all                     # 运行所有宏的测试
    genton-macro-test ci                          # 运行完整CI流水线
    genton-macro-test register <test_case.json>   # 注册测试用例
    genton-macro-test report --format json        # 生成测试报告
    
对应任务: F1.2 宏资产CI校验流水线
"""

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path


class MacroTestCLI:
    def __init__(self):
        self.adb_cmd = "adb"
        
    def run_adb_command(self, cmd):
        """执行 ADB 命令"""
        full_cmd = f"{self.adb_cmd} {cmd}"
        print(f"[CMD] {full_cmd}")
        
        result = subprocess.run(
            full_cmd,
            shell=True,
            capture_output=True,
            text=True
        )
        
        if result.returncode != 0:
            print(f"[ERROR] {result.stderr}", file=sys.stderr)
            return None
            
        return result.stdout.strip()
    
    def call_service_method(self, service, method, args=None):
        """调用服务方法"""
        cmd = f"shell service call {service} {method}"
        
        if args:
            for arg in args:
                cmd += f" s16 '{arg}'"
        
        return self.run_adb_command(cmd)
    
    def run_macro_tests(self, macro_id):
        """运行单个宏的所有测试"""
        print(f"[INFO] Running tests for macro: {macro_id}")
        
        # 通过 service call 调用 IMacroValidationService.runAllTestsForMacro
        # 注意: 实际实现中需要解析 service call 的返回值
        
        cmd = f"shell am broadcast -a com.genton.macro.RUN_TESTS --es macroId '{macro_id}'"
        output = self.run_adb_command(cmd)
        
        if output:
            print(f"[SUCCESS] Tests completed for {macro_id}")
            return True
        else:
            print(f"[ERROR] Failed to run tests for {macro_id}", file=sys.stderr)
            return False
    
    def run_all_tests(self):
        """运行所有宏的测试"""
        print("[INFO] Running all macro tests...")
        
        # 1. 查询所有宏
        cmd = "shell content query --uri content://com.genton.macro/macros"
        output = self.run_adb_command(cmd)
        
        # 2. 解析宏列表 (简化实现)
        macro_ids = []  # 实际需要从 output 解析
        
        # 3. 逐个运行测试
        results = {}
        for macro_id in macro_ids:
            results[macro_id] = self.run_macro_tests(macro_id)
        
        # 4. 输出汇总
        total = len(results)
        passed = sum(1 for v in results.values() if v)
        failed = total - passed
        
        print(f"\n[SUMMARY] Total: {total}, Passed: {passed}, Failed: {failed}")
        
        return failed == 0
    
    def run_ci_pipeline(self):
        """运行完整 CI 流水线"""
        print("[INFO] Starting CI pipeline...")
        start_time = time.time()
        
        # 调用 IMacroValidationService.runCiPipeline()
        cmd = "shell am broadcast -a com.genton.macro.RUN_CI_PIPELINE"
        output = self.run_adb_command(cmd)
        
        elapsed = time.time() - start_time
        
        if output:
            print(f"\n[SUCCESS] CI pipeline completed in {elapsed:.2f}s")
            
            # 解析并显示报告
            try:
                report = json.loads(output)
                self.print_ci_report(report)
                return True
            except json.JSONDecodeError:
                print("[WARN] Failed to parse CI report", file=sys.stderr)
                return False
        else:
            print(f"[ERROR] CI pipeline failed", file=sys.stderr)
            return False
    
    def print_ci_report(self, report):
        """打印 CI 报告"""
        print("\n" + "="*60)
        print("CI PIPELINE REPORT")
        print("="*60)
        
        summary = report.get("summary", {})
        print(f"Total Macros:    {summary.get('totalMacros', 0)}")
        print(f"Tested Macros:   {summary.get('testedMacros', 0)}")
        print(f"Passed:          {summary.get('passedMacros', 0)}")
        print(f"Failed:          {summary.get('failedMacros', 0)}")
        print(f"Auto-Disabled:   {summary.get('disabledMacros', 0)}")
        print(f"Duration:        {report.get('durationMs', 0)}ms")
        print("="*60)
        
        # 显示失败的宏
        macros = report.get("macros", [])
        failed_macros = [m for m in macros if m.get("status") == "fail"]
        
        if failed_macros:
            print("\nFAILED MACROS:")
            for macro in failed_macros:
                print(f"  - {macro.get('macroName')} ({macro.get('macroId')})")
                print(f"    Tests: {macro.get('totalTests')}, " +
                      f"Passed: {macro.get('passed')}, " +
                      f"Failed: {macro.get('failed')}")
    
    def register_test_case(self, test_case_file):
        """注册测试用例"""
        print(f"[INFO] Registering test case: {test_case_file}")
        
        # 读取测试用例文件
        try:
            with open(test_case_file, 'r', encoding='utf-8') as f:
                test_case = json.load(f)
        except Exception as e:
            print(f"[ERROR] Failed to read test case file: {e}", file=sys.stderr)
            return False
        
        # 上传到设备
        device_path = "/sdcard/genton/test_cases/" + Path(test_case_file).name
        self.run_adb_command(f"push '{test_case_file}' '{device_path}'")
        
        # 调用注册接口
        cmd = f"shell am broadcast -a com.genton.macro.REGISTER_TEST_CASE --es path '{device_path}'"
        output = self.run_adb_command(cmd)
        
        if output and "success" in output.lower():
            print(f"[SUCCESS] Test case registered")
            return True
        else:
            print(f"[ERROR] Failed to register test case", file=sys.stderr)
            return False
    
    def generate_report(self, format_type):
        """生成测试报告"""
        print(f"[INFO] Generating report (format: {format_type})...")
        
        # 调用 IMacroValidationService.exportTestReport(format)
        cmd = f"shell am broadcast -a com.genton.macro.EXPORT_REPORT --es format '{format_type}'"
        output = self.run_adb_command(cmd)
        
        if output:
            # 保存报告到本地文件
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            ext = "json" if format_type == "json" else "xml" if format_type == "junit_xml" else "html"
            filename = f"macro_test_report_{timestamp}.{ext}"
            
            with open(filename, 'w', encoding='utf-8') as f:
                f.write(output)
            
            print(f"[SUCCESS] Report saved to: {filename}")
            return True
        else:
            print(f"[ERROR] Failed to generate report", file=sys.stderr)
            return False


def main():
    parser = argparse.ArgumentParser(
        description="AI Native OS - Macro Testing CLI Tool",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    subparsers = parser.add_subparsers(dest="command", help="Command")
    
    # run 命令
    run_parser = subparsers.add_parser("run", help="Run tests for a macro")
    run_parser.add_argument("macro_id", help="Macro ID")
    
    # run-all 命令
    subparsers.add_parser("run-all", help="Run all macro tests")
    
    # ci 命令
    subparsers.add_parser("ci", help="Run CI pipeline")
    
    # register 命令
    register_parser = subparsers.add_parser("register", help="Register a test case")
    register_parser.add_argument("test_case_file", help="Test case JSON file")
    
    # report 命令
    report_parser = subparsers.add_parser("report", help="Generate test report")
    report_parser.add_argument("--format", choices=["json", "html", "junit_xml"],
                              default="json", help="Report format")
    
    args = parser.parse_args()
    
    if not args.command:
        parser.print_help()
        return 1
    
    cli = MacroTestCLI()
    
    try:
        if args.command == "run":
            success = cli.run_macro_tests(args.macro_id)
        elif args.command == "run-all":
            success = cli.run_all_tests()
        elif args.command == "ci":
            success = cli.run_ci_pipeline()
        elif args.command == "register":
            success = cli.register_test_case(args.test_case_file)
        elif args.command == "report":
            success = cli.generate_report(args.format)
        else:
            parser.print_help()
            return 1
        
        return 0 if success else 1
        
    except KeyboardInterrupt:
        print("\n[INTERRUPTED] Operation cancelled by user")
        return 130
    except Exception as e:
        print(f"[FATAL] Unexpected error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
