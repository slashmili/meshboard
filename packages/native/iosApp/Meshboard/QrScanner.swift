import AVFoundation
import UIKit
import MeshboardShared

/// Only video metadata is decoded. No photos, files, uploads, or media tracks.
final class NativeQrScanner: NSObject, QrScanner {
    weak var host: UIViewController?
    private var scanner: BoardQrViewController?
    private var events: QrScanEvents?
    private var generation = 0

    func start(events: QrScanEvents) {
        stop()
        self.events = events
        let current = generation
        // Let Compose remove its join dialog before presenting the camera sheet.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.generation == current, self.events != nil, let host = self.host else { return }
            let camera = BoardQrViewController()
            camera.modalPresentationStyle = .fullScreen
            camera.onResult = { [weak self] value in self?.finish(value: value) }
            self.scanner = camera
            host.present(camera, animated: true)
        }
    }

    private func finish(value: String?) {
        guard let events else { return }
        self.events = nil
        generation += 1
        let camera = scanner
        scanner = nil
        camera?.shutdown()
        camera?.dismiss(animated: true) {
            if let value { events.found(value: value) } else { events.cancelled() }
        }
    }

    func stop() {
        generation += 1
        events = nil
        scanner?.shutdown()
        scanner?.dismiss(animated: false)
        scanner = nil
    }
}

private final class BoardQrViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onResult: ((String?) -> Void)?
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "meshboard.qr-camera")
    private var preview: AVCaptureVideoPreviewLayer!
    private let message = UILabel()
    private let settings = UIButton(type: .system)
    private var observers: [NSObjectProtocol] = []
    private var alive = true
    private var visible = false
    private var requestingPermission = false
    // Accessed only on the capture queue.
    private var configured = false
    private var runningRequested = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        message.text = "Point the camera at a Meshboard invite. Images stay on this device."
        message.textColor = .white
        message.numberOfLines = 0
        message.textAlignment = .center
        message.accessibilityIdentifier = "qr-scanner-status"
        settings.setTitle("Open Settings", for: .normal)
        settings.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        settings.isHidden = true
        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel · Paste invite", for: .normal)
        cancel.addTarget(self, action: #selector(cancelScan), for: .touchUpInside)
        let controls = UIStackView(arrangedSubviews: [message, settings, cancel])
        controls.axis = .vertical
        controls.spacing = 16
        controls.backgroundColor = UIColor.black.withAlphaComponent(0.8)
        controls.isLayoutMarginsRelativeArrangement = true
        controls.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
        controls.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(controls)
        NSLayoutConstraint.activate([
            controls.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            controls.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            controls.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            cancel.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            settings.heightAnchor.constraint(greaterThanOrEqualToConstant: 44)
        ])
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.pause() })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.authorize() })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main) { [weak self] _ in
            self?.pause()
            self?.message.text = "The camera is unavailable. Cancel and try again, or paste the invite."
        })
        observers.append(center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) { [weak self] _ in
            self?.message.text = "Camera paused. Close other camera apps or return to Meshboard to continue."
        })
        observers.append(center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main) { [weak self] _ in self?.authorize() })
    }

    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); visible = true; authorize() }
    override func viewWillDisappear(_ animated: Bool) { visible = false; pause(); super.viewWillDisappear(animated) }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview.frame = view.bounds
        if let orientation = view.window?.windowScene?.interfaceOrientation,
           let connection = preview.connection, connection.isVideoOrientationSupported {
            switch orientation {
            case .portrait: connection.videoOrientation = .portrait
            case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
            case .landscapeLeft: connection.videoOrientation = .landscapeLeft
            case .landscapeRight: connection.videoOrientation = .landscapeRight
            default: break
            }
        }
    }

    private func authorize() {
        guard alive, visible, UIApplication.shared.applicationState == .active else { return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            settings.isHidden = true
            message.text = "Looking for a board QR code… Images stay on this device."
            startCamera()
        case .notDetermined:
            guard !requestingPermission else { return }
            requestingPermission = true
            AVCaptureDevice.requestAccess(for: .video) { [weak self] _ in
                DispatchQueue.main.async { self?.requestingPermission = false; self?.authorize() }
            }
        default:
            pause()
            message.text = "Camera access is off or restricted. Allow it in Settings, or cancel and paste the invite."
            settings.isHidden = false
        }
    }

    private func startCamera() {
        queue.async { [weak self] in
            guard let self else { return }
            self.runningRequested = true
            if !self.configured {
                guard let device = AVCaptureDevice.default(for: .video),
                      let input = try? AVCaptureDeviceInput(device: device) else { self.unavailable(); return }
                let output = AVCaptureMetadataOutput()
                self.session.beginConfiguration()
                guard self.session.canAddInput(input) else { self.session.commitConfiguration(); self.unavailable(); return }
                self.session.addInput(input)
                guard self.session.canAddOutput(output) else {
                    self.session.removeInput(input); self.session.commitConfiguration(); self.unavailable(); return
                }
                self.session.addOutput(output)
                guard output.availableMetadataObjectTypes.contains(.qr) else {
                    self.session.removeInput(input); self.session.removeOutput(output)
                    self.session.commitConfiguration(); self.unavailable(); return
                }
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
                self.session.commitConfiguration()
                self.configured = true
            }
            if self.runningRequested && !self.session.isRunning { self.session.startRunning() }
        }
    }

    private func unavailable() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.alive else { return }
            self.message.text = "No camera is available. Cancel and paste the full board invite."
        }
    }
    private func pause() {
        queue.async { [self] in runningRequested = false; if session.isRunning { session.stopRunning() } }
    }
    func shutdown() {
        alive = false
        onResult = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        pause()
    }
    @objc private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
    @objc private func cancelScan() { onResult?(nil) }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard alive, visible, UIApplication.shared.applicationState == .active,
              let value = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        let callback = onResult
        onResult = nil
        pause()
        callback?(value)
    }
    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }
}
