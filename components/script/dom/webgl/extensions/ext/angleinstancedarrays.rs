/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

use dom_struct::dom_struct;
use js::context::JSContext;
use script_bindings::reflector::{Reflector, reflect_dom_object};
use servo_canvas_traits::webgl::WebGLVersion;

use super::{WebGLExtension, WebGLExtensionSpec, WebGLExtensions};
use crate::dom::bindings::codegen::Bindings::ANGLEInstancedArraysBinding::{
    ANGLEInstancedArraysConstants, ANGLEInstancedArraysMethods,
};
use crate::dom::bindings::reflector::DomGlobal;
use crate::dom::bindings::root::{Dom, DomRoot};
use crate::dom::webgl::webglrenderingcontext::WebGLRenderingContext;

#[dom_struct]
pub(crate) struct ANGLEInstancedArrays {
    reflector_: Reflector,
    ctx: Dom<WebGLRenderingContext>,
}

impl ANGLEInstancedArrays {
    fn new_inherited(ctx: &WebGLRenderingContext) -> Self {
        Self {
            reflector_: Reflector::new(),
            ctx: Dom::from_ref(ctx),
        }
    }
}

impl WebGLExtension for ANGLEInstancedArrays {
    type Extension = Self;

    fn new(cx: &mut JSContext, ctx: &WebGLRenderingContext) -> DomRoot<Self> {
        reflect_dom_object(
            cx,
            Box::new(ANGLEInstancedArrays::new_inherited(ctx)),
            &*ctx.global(),
        )
    }

    fn spec() -> WebGLExtensionSpec {
        WebGLExtensionSpec::Specific(WebGLVersion::WebGL1)
    }

    fn is_supported(ext: &WebGLExtensions) -> bool {
        // namsan-spike: the four names above are desktop-GL-era
        // extension strings. On GLES3 (every current Android driver)
        // instanced drawing is CORE, the strings are absent, and the
        // gate silently hid a fully-working implementation — breaking
        // WebGL1 stacks that require this extension (Phaser 4 throws
        // "ANGLE_instanced_arrays extension not supported" and the
        // page never leaves its loading screen). The instanced draw
        // commands exist unconditionally in the command layer, so
        // expose the extension whenever the context exists; a truly
        // incapable driver surfaces the failure at draw time exactly
        // as it would have without the extension.
        ext.supports_any_gl_extension(&[
            "GL_ANGLE_instanced_arrays",
            "GL_ARB_instanced_arrays",
            "GL_EXT_instanced_arrays",
            "GL_NV_instanced_arrays",
        ]) || true
    }

    fn enable(ext: &WebGLExtensions) {
        ext.enable_get_vertex_attrib_name(
            ANGLEInstancedArraysConstants::VERTEX_ATTRIB_ARRAY_DIVISOR_ANGLE,
        );
    }

    fn name() -> &'static str {
        "ANGLE_instanced_arrays"
    }
}

impl ANGLEInstancedArraysMethods<crate::DomTypeHolder> for ANGLEInstancedArrays {
    /// <https://www.khronos.org/registry/webgl/extensions/ANGLE_instanced_arrays/>
    fn DrawArraysInstancedANGLE(
        &self,
        cx: &mut JSContext,
        mode: u32,
        first: i32,
        count: i32,
        primcount: i32,
    ) {
        handle_potential_webgl_error!(
            self.ctx,
            self.ctx
                .draw_arrays_instanced(cx, mode, first, count, primcount)
        )
    }

    /// <https://www.khronos.org/registry/webgl/extensions/ANGLE_instanced_arrays/>
    fn DrawElementsInstancedANGLE(
        &self,
        cx: &mut JSContext,
        mode: u32,
        count: i32,
        type_: u32,
        offset: i64,
        primcount: i32,
    ) {
        handle_potential_webgl_error!(
            self.ctx,
            self.ctx
                .draw_elements_instanced(cx, mode, count, type_, offset, primcount)
        )
    }

    fn VertexAttribDivisorANGLE(&self, cx: &mut JSContext, index: u32, divisor: u32) {
        self.ctx.vertex_attrib_divisor(cx, index, divisor);
    }
}
