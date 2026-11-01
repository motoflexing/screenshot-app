import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SnapBubble',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const CaptureScreen(),
    );
  }
}

class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> with WidgetsBindingObserver {
  static const platform = MethodChannel('com.motoflexing.snapbubble/capture');

  String _lastResult = 'Ready';
  bool _isServiceRunning = false;
  bool _hasOverlayPermission = false;
  bool _checkingPermission = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkOverlayPermission();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkOverlayPermission();
    }
  }

  Future<void> _checkOverlayPermission() async {
    if (_checkingPermission) return;
    setState(() => _checkingPermission = true);
    try {
      final bool hasPermission = await platform.invokeMethod('hasOverlayPermission');
      setState(() => _hasOverlayPermission = hasPermission);
    } on PlatformException catch (e) {
      setState(() => _lastResult = 'Error checking overlay: ${e.message}');
    } finally {
      setState(() => _checkingPermission = false);
    }
  }

  Future<void> _requestOverlayPermission() async {
    try {
      await platform.invokeMethod('requestOverlayPermission');
      // Permission will be re-checked when app resumes
    } on PlatformException catch (e) {
      setState(() => _lastResult = 'Error requesting overlay: ${e.message}');
    }
  }

  Future<void> _startCapture() async {
    if (!_hasOverlayPermission) {
      setState(() => _lastResult = 'Overlay permission required. Grant it first.');
      return;
    }
    setState(() => _lastResult = 'Starting...');
    try {
      final String result = await platform.invokeMethod('start');
      setState(() {
        _lastResult = 'Start: $result';
        _isServiceRunning = true;
      });
    } on PlatformException catch (e) {
      setState(() => _lastResult = 'Error: ${e.message}');
    }
  }

  Future<void> _captureNow() async {
    setState(() => _lastResult = 'Capturing...');
    try {
      // Use a timeout so it never hangs silently
      final String result = await platform.invokeMethod('captureNow').timeout(
        const Duration(seconds: 10),
        onTimeout: () => 'ERROR: Capture timeout (10s)',
      );
      setState(() => _lastResult = 'Capture: $result');
    } on PlatformException catch (e) {
      setState(() => _lastResult = 'Error: ${e.message}');
    }
  }

  Future<void> _stopCapture() async {
    setState(() => _lastResult = 'Stopping...');
    try {
      final String result = await platform.invokeMethod('stop');
      setState(() {
        _lastResult = 'Stop: $result';
        _isServiceRunning = false;
      });
    } on PlatformException catch (e) {
      setState(() => _lastResult = 'Error: ${e.message}');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SnapBubble Screen Capture'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (!_hasOverlayPermission) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.orange[100],
                  border: Border.all(color: Colors.orange[400]!),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.warning_amber_rounded, color: Colors.orange),
                    const SizedBox(width: 8),
                    const Expanded(child: Text('Overlay permission required for floating bubble')),
                    const SizedBox(width: 8),
                    ElevatedButton(
                      onPressed: _checkingPermission ? null : _requestOverlayPermission,
                      child: _checkingPermission
                          ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Grant'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
            ],
            ElevatedButton(
              onPressed: (_isServiceRunning || !_hasOverlayPermission) ? null : _startCapture,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                backgroundColor: Colors.green,
                foregroundColor: Colors.white,
              ),
              child: const Text('Start', style: TextStyle(fontSize: 18)),
            ),
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: _isServiceRunning ? _captureNow : null,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                backgroundColor: Colors.blue,
                foregroundColor: Colors.white,
              ),
              child: const Text('Capture Now', style: TextStyle(fontSize: 18)),
            ),
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: _isServiceRunning ? _stopCapture : null,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                backgroundColor: Colors.red,
                foregroundColor: Colors.white,
              ),
              child: const Text('Stop', style: TextStyle(fontSize: 18)),
            ),
            const SizedBox(height: 24),
            const Text('Last Result:', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Expanded(
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey[100],
                  border: Border.all(color: Colors.grey[400]!),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SingleChildScrollView(
                  child: SelectableText(
                    _lastResult,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 14),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}