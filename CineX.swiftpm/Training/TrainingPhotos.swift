import CoreGraphics
import Foundation
import ImageIO
import simd

extension SplatTrainer {
    /// Training photos are this wide (Android: 256 x 144 for its 16:9 photos; the iPhone's are 4:3, so 256 x 192).
    static let trainingWidth = 256

    /// A saved keyframe photo shrunk to the training size, with its camera (`KeyframeRecord`'s fields).
    /// The photo is in the sensor's landscape orientation, the same one its intrinsics describe.
    static func view(photo url: URL, photoWidth: Int, photoHeight: Int, fx: Float, fy: Float, cx: Float, cy: Float,
                     cameraToWorld: simd_float4x4) -> View? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let W = trainingWidth
        let H = max(1, Int((Float(W) * Float(photoHeight) / Float(photoWidth)).rounded()))
        var rgba = [UInt8](repeating: 0, count: W * H * 4)
        let drawn = rgba.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: W, height: H, bitsPerComponent: 8, bytesPerRow: W * 4,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            // High quality averages the pixels each training pixel covers, like Android's box filter.
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: W, height: H))
            return true
        }
        guard drawn else { return nil }
        let sx = Float(W) / Float(photoWidth), sy = Float(H) / Float(photoHeight)
        return View(worldToCamera: cameraToWorld.inverse, fx: fx * sx, fy: fy * sy, cx: cx * sx, cy: cy * sy,
                    width: W, height: H, rgba: rgba)
    }
}
