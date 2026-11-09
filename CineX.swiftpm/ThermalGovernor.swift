import Foundation

/// Paces heavy work by the phone's heat (rk 9 Nov): full speed when cool, smaller batches and short rests when
/// warm, a pause when hot until it has cooled down. Never aborts. Used by processing after the scan.
enum ThermalGovernor {
    /// For tests: pretend the phone is at this state (launch with -fakeThermal fair|serious|critical).
    static let fake: ProcessInfo.ThermalState? = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-fakeThermal"), i + 1 < args.count else { return nil }
        return ["nominal": .nominal, "fair": .fair, "serious": .serious, "critical": .critical][args[i + 1]]
    }()

    static var state: ProcessInfo.ThermalState { fake ?? ProcessInfo.processInfo.thermalState }

    /// How much work to do per batch, 0…1 (Low Power Mode counts as warm).
    static var batchScale: Float {
        switch state {
        case .nominal: return ProcessInfo.processInfo.isLowPowerModeEnabled ? 0.6 : 1
        case .fair: return 0.6
        case .serious: return 0.3
        default: return 0.15
        }
    }

    /// Call between batches: rests a little when warm, and waits while hot (calling `note` with what to show,
    /// then nil when it continues).
    /// For short GPU bursts (fusion on the GPU): race to idle (rk 9 Nov: "less time working overall per scan").
    /// Finishing in seconds and then resting heats the phone less than trickling along for minutes, so only a
    /// critical phone waits.
    static func paceBurst(note: @escaping @Sendable (String?) -> Void) async {
        guard state == .critical else { return }
        await pace(note: note)
    }

    static func pace(note: @escaping @Sendable (String?) -> Void) async {
        switch state {
        case .nominal, .fair:
            if ProcessInfo.processInfo.isLowPowerModeEnabled { try? await Task.sleep(nanoseconds: 50_000_000) }
        case .serious:
            // A short rest only (was 0.4 s every batch: a hot phone's rebuild of a 6-minute scan took minutes of
            // mostly waiting, keeping it busy and warm for longer).
            try? await Task.sleep(nanoseconds: 100_000_000)
        default:
            note("Cooling down, continuing shortly")
            while state == .critical, fake == nil || fake == .critical {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if fake == .critical { break } // a test: don't wait forever
            }
            note(nil)
        }
    }
}
