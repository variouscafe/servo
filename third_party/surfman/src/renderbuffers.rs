//! A utility module for backends that wrap surfaces in OpenGL FBOs.

use crate::context::{ContextAttributeFlags, ContextAttributes};
use crate::gl;
use crate::Gl;
use std::thread;

use euclid::default::Size2D;
use gl::Renderbuffer;
use glow::HasContext;

pub(crate) enum Renderbuffers {
    IndividualDepthStencil {
        depth: Option<Renderbuffer>,
        stencil: Option<Renderbuffer>,
    },
    CombinedDepthStencil(Option<Renderbuffer>),
}

impl Drop for Renderbuffers {
    fn drop(&mut self) {
        match *self {
            Renderbuffers::IndividualDepthStencil {
                depth: None,
                stencil: None,
            }
            | Renderbuffers::CombinedDepthStencil(None) => {}
            _ => {
                if !thread::panicking() {
                    panic!("Should have destroyed the FBO renderbuffers with `destroy()`!")
                }
            }
        }
    }
}

impl Renderbuffers {
    pub(crate) fn new(
        gl: &Gl,
        size: &Size2D<i32>,
        attributes: &ContextAttributes,
    ) -> Renderbuffers {
        unsafe {
            let wants_depth = attributes.flags.contains(ContextAttributeFlags::DEPTH);
            let wants_stencil = attributes.flags.contains(ContextAttributeFlags::STENCIL);

            // namsan(원인 6호, 2026-10-30 개정): 폰 실GPU에서 결합형 DEPTH24_STENCIL8
            // 단일 렌더버퍼의 깊이가 래스터라이저에 반영되지 않는 병리(DEPTH_BITS=0,
            // 자기 z 비교 서명)를 피하려 분리형을 1차로 시도한다. 단, 드라이버에 따라
            // 분리형 첨부가 FBO 불완전로 거부되는 경로(에뮬레이터 ANGLE의 게임
            // 컨텍스트에서 관측 — android_surface.rs의 debug_assert 패닉)가 있으므로,
            // 완전성 검사 후 결합형으로 폴백한다. 어느 쪽이 채택됐는지 로그로 남긴다.
            // 호출부는 new() 전에 컬러 첨부를 바인드하므로 여기서 상태 검사가 가능하다.
            if cfg!(any(target_os = "android", target_os = "ohos")) {
                let mut individual = Self::create_individual(gl, size, wants_depth, wants_stencil);
                individual.bind_to_current_framebuffer(gl);
                let status = gl.check_framebuffer_status(gl::FRAMEBUFFER);
                if status == gl::FRAMEBUFFER_COMPLETE {
                    eprintln!("namsan-surfman: 분리형 채택 d={wants_depth} s={wants_stencil}");
                    return individual;
                }
                eprintln!(
                    "namsan-surfman: 분리형 불완전(0x{status:x}) — 결합형 폴백 d={wants_depth} s={wants_stencil}"
                );
                individual.destroy(gl);
                let combined = Self::create_combined(gl, size);
                combined.bind_to_current_framebuffer(gl);
                return combined;
            }

            // 비(非)안드로이드: 업스트림 원본 로직 — 둘 다 요청 시 결합형 우선.
            if wants_depth && wants_stencil {
                return Self::create_combined(gl, size);
            }
            Self::create_individual(gl, size, wants_depth, wants_stencil)
        }
    }

    unsafe fn create_combined(gl: &Gl, size: &Size2D<i32>) -> Renderbuffers {
        let renderbuffer = gl.create_renderbuffer().unwrap();
        gl.bind_renderbuffer(gl::RENDERBUFFER, Some(renderbuffer));
        gl.renderbuffer_storage(
            gl::RENDERBUFFER,
            gl::DEPTH24_STENCIL8,
            size.width,
            size.height,
        );
        gl.bind_renderbuffer(gl::RENDERBUFFER, None);
        Renderbuffers::CombinedDepthStencil(Some(renderbuffer))
    }

    unsafe fn create_individual(
        gl: &Gl,
        size: &Size2D<i32>,
        wants_depth: bool,
        wants_stencil: bool,
    ) -> Renderbuffers {
        let (mut depth_renderbuffer, mut stencil_renderbuffer) = (None, None);
        if wants_depth {
            depth_renderbuffer = Some(gl.create_renderbuffer().unwrap());
            gl.bind_renderbuffer(gl::RENDERBUFFER, depth_renderbuffer);
            gl.renderbuffer_storage(
                gl::RENDERBUFFER,
                gl::DEPTH_COMPONENT24,
                size.width,
                size.height,
            );
        }
        if wants_stencil {
            stencil_renderbuffer = Some(gl.create_renderbuffer().unwrap());
            gl.bind_renderbuffer(gl::RENDERBUFFER, stencil_renderbuffer);
            gl.renderbuffer_storage(
                gl::RENDERBUFFER,
                gl::STENCIL_INDEX8,
                size.width,
                size.height,
            );
        }
        gl.bind_renderbuffer(gl::RENDERBUFFER, None);

        Renderbuffers::IndividualDepthStencil {
            depth: depth_renderbuffer,
            stencil: stencil_renderbuffer,
        }
    }

    pub(crate) fn bind_to_current_framebuffer(&self, gl: &Gl) {
        unsafe {
            match *self {
                Renderbuffers::CombinedDepthStencil(renderbuffer) => {
                    if renderbuffer.is_some() {
                        gl.framebuffer_renderbuffer(
                            gl::FRAMEBUFFER,
                            gl::DEPTH_STENCIL_ATTACHMENT,
                            gl::RENDERBUFFER,
                            renderbuffer,
                        );
                    }
                }
                Renderbuffers::IndividualDepthStencil {
                    depth: depth_renderbuffer,
                    stencil: stencil_renderbuffer,
                } => {
                    if depth_renderbuffer.is_some() {
                        gl.framebuffer_renderbuffer(
                            gl::FRAMEBUFFER,
                            gl::DEPTH_ATTACHMENT,
                            gl::RENDERBUFFER,
                            depth_renderbuffer,
                        );
                    }
                    if stencil_renderbuffer.is_some() {
                        gl.framebuffer_renderbuffer(
                            gl::FRAMEBUFFER,
                            gl::STENCIL_ATTACHMENT,
                            gl::RENDERBUFFER,
                            stencil_renderbuffer,
                        );
                    }
                }
            }
        }
    }

    pub(crate) fn destroy(&mut self, gl: &Gl) {
        unsafe {
            gl.bind_renderbuffer(gl::RENDERBUFFER, None);

            match *self {
                Renderbuffers::CombinedDepthStencil(ref mut renderbuffer) => {
                    if let Some(renderbuffer) = renderbuffer.take() {
                        gl.delete_renderbuffer(renderbuffer);
                    }
                }
                Renderbuffers::IndividualDepthStencil {
                    depth: ref mut depth_renderbuffer,
                    stencil: ref mut stencil_renderbuffer,
                } => {
                    if let Some(stencil_renderbuffer) = stencil_renderbuffer.take() {
                        gl.delete_renderbuffer(stencil_renderbuffer);
                    }
                    if let Some(depth_renderbuffer) = depth_renderbuffer.take() {
                        gl.delete_renderbuffer(depth_renderbuffer);
                    }
                }
            }
        }
    }
}
