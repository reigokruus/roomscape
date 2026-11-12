// Brush's C interface (apps/brush-c/src/lib.rs, with RoomScape's additions: tools/brush-ios/brush-ios.patch).
// Brush: https://github.com/ArthurBrussee/brush, Apache-2.0.
#ifndef BRUSH_C_H
#define BRUSH_C_H
#include <stdint.h>

typedef enum { BrushTrainSuccess = 0, BrushTrainError = 1 } BrushTrainExitCode;

// Rust `#[repr(C)] enum ProgressMessage { NewProcess, Training { iter: u32 }, DoneTraining }`: a tag and its data.
typedef enum { BrushNewProcess = 0, BrushTraining = 1, BrushDoneTraining = 2 } BrushProgressTag;
typedef struct { uint32_t tag; uint32_t iter; } BrushProgressMessage;

typedef struct {
    uint32_t total_train_steps;
    uint32_t refine_every;
    uint32_t max_resolution;
    uint32_t export_every;
    const char *output_path;    // a folder: the trained splat is written there as a .ply
    // RoomScape additions
    uint32_t max_splats;        // 0 = Brush's default (10 million)
    uint32_t sh_degree;         // 0-3, 255 = Brush's default
    const uint8_t *cancel;      // non-zero stops training (returns BrushTrainError)
} BrushTrainOptions;

typedef void (*BrushProgressCallback)(BrushProgressMessage message, void *user_data);

// Trains from a dataset folder (Nerfstudio transforms.json + images + starting points) and writes the splat.
// Blocks until done: call it on a background thread.
BrushTrainExitCode train_and_save(const char *dataset_path, const BrushTrainOptions *options,
                                  BrushProgressCallback progress_callback, void *user_data);
#endif
