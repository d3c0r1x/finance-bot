package main

import "testing"

func TestConfiguredF43WorkerIsOptIn(t *testing.T) {
	worker, err := configuredF43Worker("", "", "", "")
	if err != nil || worker != nil {
		t.Fatalf("disabled config = (%v, %v)", worker, err)
	}
	if _, err := configuredF43Worker("sometimes", "", "", ""); err == nil {
		t.Fatal("expected strict boolean validation")
	}
}

func TestConfiguredF43WorkerRequiresCoreUrlAndTokenWhenEnabled(t *testing.T) {
	if _, err := configuredF43Worker("true", "", "secret", ""); err == nil {
		t.Fatal("expected missing Core URL error")
	}
	if _, err := configuredF43Worker("true", "http://core.local", "", ""); err == nil {
		t.Fatal("expected missing service token error")
	}
	if _, err := configuredF43Worker("true", "http://core.local", "secret", "0s"); err == nil {
		t.Fatal("expected non-positive poll interval error")
	}
}

func TestConfiguredF43WorkerAcceptsValidConfiguration(t *testing.T) {
	worker, err := configuredF43Worker("true", "http://core.local", "secret", "250ms")
	if err != nil || worker == nil {
		t.Fatalf("valid config = (%v, %v)", worker, err)
	}
}
