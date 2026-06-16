# jdelta

JVM Semantic Delta Engine (AI를 활용하여 기술적 호기심을 해결하고 오픈소스를 구현해보고 있습니다.)

`jdelta`는 JVM Semantic Delta Engine이다.

목표는 한 가지 어려운 질문에 답하는 것이다.

> 이 JVM 프로젝트에서 무엇이 바뀌었고, 안전하게 다시 해야 하는 최소 작업은 무엇인가?

첫 구현 목표는 새 빌드 도구가 아니다. JVM build, test, generated source, 그리고 장기적으로 Spring runtime graph까지 연결하는 semantic invalidation layer를 만드는 것이다.

프로젝트 기준 문서:

- [docs/JDELTA_DESIGN.md](docs/JDELTA_DESIGN.md)
