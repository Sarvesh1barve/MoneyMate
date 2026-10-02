import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Store } from './store';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {
  readonly store = inject(Store);
  constructor() {
    void this.store.init();
  }
}
