/*
 * WebAssembly entry point.
 *
 * The exported functions themselves live in x264_kmp.c. This translation unit
 * takes their addresses so the linker definitely pulls that object out of the
 * static archive; without a reference the archive member would be dropped and
 * the exports listed in CMakeLists.txt would be missing.
 */
#include "x264_kmp.h"

typedef void (*xk_wasm_export)(void);

static const xk_wasm_export xk_wasm_exports[] = {
    (xk_wasm_export)xk_version_packed,
    (xk_wasm_export)xk_version_string,
    (xk_wasm_export)xk_api_build,
    (xk_wasm_export)xk_params_create,
    (xk_wasm_export)xk_params_free,
    (xk_wasm_export)xk_params_parse,
    (xk_wasm_export)xk_params_apply_preset,
    (xk_wasm_export)xk_params_apply_tune,
    (xk_wasm_export)xk_params_apply_profile,
    (xk_wasm_export)xk_params_validate,
    (xk_wasm_export)xk_params_get_int,
    (xk_wasm_export)xk_params_set_log_level,
    (xk_wasm_export)xk_encoder_open,
    (xk_wasm_export)xk_encoder_close,
    (xk_wasm_export)xk_encoder_parameters,
    (xk_wasm_export)xk_encoder_headers,
    (xk_wasm_export)xk_encode_planes,
    (xk_wasm_export)xk_encode_packed,
    (xk_wasm_export)xk_flush,
    (xk_wasm_export)xk_output_nal_count,
    (xk_wasm_export)xk_output_nal_type,
    (xk_wasm_export)xk_output_nal_data,
    (xk_wasm_export)xk_output_slice_type,
    (xk_wasm_export)xk_output_pts,
    (xk_wasm_export)xk_output_dts,
    (xk_wasm_export)xk_output_blob,
    (xk_wasm_export)xk_alloc,
    (xk_wasm_export)xk_free,
};

/* Keep the table observable so no optimiser removes it. */
const xk_wasm_export *xk_wasm_keep_exports(void);

const xk_wasm_export *xk_wasm_keep_exports(void) { return xk_wasm_exports; }
